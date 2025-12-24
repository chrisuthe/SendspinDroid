package player

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"sync"
	"time"

	"github.com/Resonate-Protocol/resonate-go/pkg/audio"
	"github.com/Resonate-Protocol/resonate-go/pkg/discovery"
	"github.com/Resonate-Protocol/resonate-go/pkg/protocol"
	sendsync "github.com/Resonate-Protocol/resonate-go/pkg/sync"
	"github.com/gorilla/websocket"
	"github.com/hashicorp/mdns"
)

// PlayerCallback defines the interface for receiving player events.
//
// Event sources:
// - OnServerDiscovered: mDNS discovery finds a SendSpin server
// - OnConnected/OnDisconnected: WebSocket connection state changes
// - OnGroupUpdate: group/update protocol messages (group-level state)
// - OnMetadataUpdate: server/state protocol messages with metadata (track info)
// - OnArtwork: binary artwork chunks (message types 8-11)
// - OnError: any error condition
type PlayerCallback interface {
	OnServerDiscovered(name string, address string)
	OnConnected(serverName string)
	OnDisconnected()
	OnStateChanged(state string)
	// OnGroupUpdate is called when a group/update message is received.
	// Contains group-level state: groupId, groupName, playbackState.
	OnGroupUpdate(groupId string, groupName string, playbackState string)
	// OnMetadataUpdate is called when track metadata changes.
	// Contains: title, artist, album, artworkUrl, durationMs, positionMs.
	OnMetadataUpdate(title string, artist string, album string, artworkUrl string, durationMs int64, positionMs int64)
	// OnArtwork is called when binary artwork data is received (message types 8-11).
	OnArtwork(imageData []byte)
	OnError(message string)
}

// Player represents the SendSpin player instance
type Player struct {
	mu       sync.Mutex
	ctx      context.Context
	cancel   context.CancelFunc
	callback PlayerCallback

	// Player state
	isRunning    bool
	isConnected  bool
	currentState string

	// SendSpin components
	protoClient *protocol.Client
	clockSync   *sendsync.ClockSync
	conn        *websocket.Conn

	// Connection-specific context for canceling the message handler goroutine
	// This is separate from ctx which is for the entire player lifecycle
	connCtx    context.Context
	connCancel context.CancelFunc

	// Audio state
	currentFormat *audio.Format
	audioChannel  chan []byte

	// Server info
	deviceName string
	serverAddr string

	// Discovery manager
	discoveryMgr *discovery.Manager
}

// NewPlayer creates a new SendSpin player instance
func NewPlayer(deviceName string, callback PlayerCallback) *Player {
	ctx, cancel := context.WithCancel(context.Background())

	p := &Player{
		ctx:          ctx,
		cancel:       cancel,
		callback:     callback,
		currentState: "stopped",
		deviceName:   deviceName,
		audioChannel: make(chan []byte, 100), // Increased buffer to prevent dropping chunks
	}

	log.Printf("Player created: %s", deviceName)
	return p
}

// StartDiscovery begins discovering SendSpin servers on the network
func (p *Player) StartDiscovery() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.isRunning {
		return fmt.Errorf("discovery already running")
	}

	p.isRunning = true

	// Start discovery in background
	go p.discoverServers()

	return nil
}

// StopDiscovery stops server discovery
func (p *Player) StopDiscovery() {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.isRunning = false

	// Clean up discovery manager
	if p.discoveryMgr != nil {
		p.discoveryMgr.Stop()
		p.discoveryMgr = nil
	}
}

// Connect connects to a SendSpin server
func (p *Player) Connect(serverAddress string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.isConnected {
		return fmt.Errorf("already connected")
	}

	log.Printf("Connecting to server: %s", serverAddress)

	// Build WebSocket URL with correct /sendspin path
	u := url.URL{Scheme: "ws", Host: serverAddress, Path: "/sendspin"}
	log.Printf("Connecting to %s", u.String())

	// Establish WebSocket connection
	conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	if err != nil {
		return fmt.Errorf("failed to connect to server: dial failed: %w", err)
	}

	// Perform SendSpin protocol handshake
	if err := p.performHandshake(conn); err != nil {
		conn.Close()
		return fmt.Errorf("failed to connect to server: handshake failed: %w", err)
	}

	// Create protocol client config for message handling
	config := protocol.Config{
		ServerAddr: serverAddress,
		ClientID:   p.deviceName,
		Name:       p.deviceName,
		Version:    1,
		DeviceInfo: protocol.DeviceInfo{
			ProductName:     "SendSpinDroid",
			Manufacturer:    "SendSpin",
			SoftwareVersion: "1.0.0",
		},
		PlayerSupport: protocol.PlayerSupport{
			SupportFormats: []protocol.AudioFormat{
				{
					Codec:      "pcm",
					SampleRate: 48000,
					Channels:   2,
					BitDepth:   16,
				},
			},
			BufferCapacity: 10,
		},
		MetadataSupport: protocol.MetadataSupport{
			SupportPictureFormats: []string{},
		},
		VisualizerSupport: protocol.VisualizerSupport{},
	}

	// Create protocol client (we'll use its message types but manage connection ourselves)
	client := protocol.NewClient(config)
	p.protoClient = client

	// Store the WebSocket connection for reading messages
	p.conn = conn

	// Create clock sync instance
	p.clockSync = sendsync.NewClockSync()

	// Create connection-specific context for the message handler goroutine
	// This allows us to cancel just this goroutine when disconnecting
	p.connCtx, p.connCancel = context.WithCancel(p.ctx)

	// Start background goroutine for message handling
	go p.handleProtocolMessages()

	// Update connection state
	p.isConnected = true
	p.serverAddr = serverAddress

	if p.callback != nil {
		p.callback.OnConnected(serverAddress)
	}

	log.Printf("Successfully connected to server: %s", serverAddress)
	return nil
}

// performHandshake sends client/hello and waits for server/hello
func (p *Player) performHandshake(conn *websocket.Conn) error {
	// Send client/hello
	// Request roles needed for full functionality (matching C# reference implementation):
	// - controller@v1: send playback commands (play/pause/next/etc)
	// - player@v1: receive and play audio
	// - metadata@v1: receive track metadata (title, artist, album, artwork_url)
	// Note: artwork@v1 role not requested - we use artwork_url from metadata instead
	hello := map[string]interface{}{
		"type": "client/hello",
		"payload": map[string]interface{}{
			"client_id": p.deviceName,
			"name":      p.deviceName,
			"version":   1,
			"supported_roles": []string{"controller@v1", "player@v1", "metadata@v1"},
			"device_info": map[string]interface{}{
				"product_name":     "SendSpinDroid",
				"manufacturer":     "SendSpin",
				"software_version": "1.0.0",
			},
			"player_support": map[string]interface{}{
				"supported_formats": []map[string]interface{}{
					{
						"codec":       "pcm",
						"sample_rate": 48000,
						"channels":    2,
						"bit_depth":   16,
					},
				},
				"buffer_capacity":     32000000, // 32MB like reference implementation
				"supported_commands": []string{"volume", "mute"},
			},
		},
	}

	// Log the exact JSON being sent
	helloJSON, _ := json.Marshal(hello)
	log.Printf("Sending client/hello: %s", string(helloJSON))

	if err := conn.WriteJSON(hello); err != nil {
		return fmt.Errorf("failed to send client/hello: %w", err)
	}

	log.Printf("client/hello sent, waiting for server/hello...")

	// Wait for server/hello (10 second timeout like reference implementation)
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	var serverMsg map[string]interface{}
	if err := conn.ReadJSON(&serverMsg); err != nil {
		return fmt.Errorf("failed to read server/hello: %w", err)
	}
	conn.SetReadDeadline(time.Time{})

	msgType, ok := serverMsg["type"].(string)
	if !ok || msgType != "server/hello" {
		return fmt.Errorf("expected server/hello, got %v", msgType)
	}

	log.Printf("Handshake complete with server")

	// Send initial client/state with synchronized state
	state := map[string]interface{}{
		"type": "client/state",
		"payload": map[string]interface{}{
			"state": "synchronized",
			"player": map[string]interface{}{
				"volume": 100,
				"muted":  false,
			},
		},
	}

	stateJSON, _ := json.Marshal(state)
	log.Printf("Sending client/state: %s", string(stateJSON))

	if err := conn.WriteJSON(state); err != nil {
		return fmt.Errorf("failed to send client/state: %w", err)
	}

	log.Printf("client/state sent")
	return nil
}

// Disconnect disconnects from the current server
func (p *Player) Disconnect() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.isConnected {
		return fmt.Errorf("not connected")
	}

	log.Printf("Disconnecting from server")

	// Cancel connection-specific context FIRST to signal the message handler
	// goroutine to exit cleanly before we close the WebSocket
	if p.connCancel != nil {
		p.connCancel()
		p.connCancel = nil
	}

	// Close protocol client
	if p.protoClient != nil {
		// Note: Close() doesn't return error in current API
		p.protoClient = nil
	}

	// Close WebSocket connection - the goroutine should already be exiting
	// due to context cancellation, but this ensures cleanup
	if p.conn != nil {
		if err := p.conn.Close(); err != nil {
			log.Printf("Error closing WebSocket: %v", err)
		}
		p.conn = nil
	}

	// Drain audio channel to unblock any readers waiting for data
	// Don't close it - we may reconnect later
drainLoop:
	for {
		select {
		case <-p.audioChannel:
			// Discard pending audio data
		default:
			break drainLoop
		}
	}

	// Reset state
	p.isConnected = false
	p.serverAddr = ""
	p.currentFormat = nil
	p.clockSync = nil

	if p.callback != nil {
		p.callback.OnDisconnected()
	}

	return nil
}

// SendCommand sends a control command to the server (play, pause, stop, next, previous, etc.)
// This follows the SendSpin protocol: client/command message with controller payload
func (p *Player) SendCommand(command string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.isConnected || p.conn == nil {
		return fmt.Errorf("not connected to server")
	}

	log.Printf("Sending command to server: %s", command)

	// Build client/command message per SendSpin protocol
	msg := map[string]interface{}{
		"type": "client/command",
		"payload": map[string]interface{}{
			"controller": map[string]interface{}{
				"command": command,
			},
		},
	}

	// Log the message being sent
	msgJSON, _ := json.Marshal(msg)
	log.Printf("Sending client/command: %s", string(msgJSON))

	if err := p.conn.WriteJSON(msg); err != nil {
		return fmt.Errorf("failed to send command: %w", err)
	}

	return nil
}

// SendVolumeCommand sends a volume control command to the server
func (p *Player) SendVolumeCommand(volume int) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.isConnected || p.conn == nil {
		return fmt.Errorf("not connected to server")
	}

	log.Printf("Sending volume command to server: %d", volume)

	msg := map[string]interface{}{
		"type": "client/command",
		"payload": map[string]interface{}{
			"controller": map[string]interface{}{
				"command": "volume",
				"volume":  volume,
			},
		},
	}

	msgJSON, _ := json.Marshal(msg)
	log.Printf("Sending client/command: %s", string(msgJSON))

	if err := p.conn.WriteJSON(msg); err != nil {
		return fmt.Errorf("failed to send volume command: %w", err)
	}

	return nil
}

// Play starts playback by sending play command to server
func (p *Player) Play() error {
	// Send command to server (don't hold lock during network call)
	if err := p.SendCommand("play"); err != nil {
		return err
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	log.Printf("Play command sent")
	p.currentState = "playing"

	if p.callback != nil {
		p.callback.OnStateChanged("playing")
	}

	return nil
}

// Pause pauses playback by sending pause command to server
func (p *Player) Pause() error {
	// Send command to server
	if err := p.SendCommand("pause"); err != nil {
		return err
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	log.Printf("Pause command sent")
	p.currentState = "paused"

	if p.callback != nil {
		p.callback.OnStateChanged("paused")
	}

	return nil
}

// Stop stops playback by sending stop command to server
func (p *Player) Stop() error {
	// Send command to server
	if err := p.SendCommand("stop"); err != nil {
		return err
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	log.Printf("Stop command sent")
	p.currentState = "stopped"

	if p.callback != nil {
		p.callback.OnStateChanged("stopped")
	}

	return nil
}

// Next skips to the next track
func (p *Player) Next() error {
	return p.SendCommand("next")
}

// Previous goes to the previous track
func (p *Player) Previous() error {
	return p.SendCommand("previous")
}

// SetVolume sets the playback volume (0.0 to 1.0) and sends to server
func (p *Player) SetVolume(volume float64) error {
	if volume < 0.0 || volume > 1.0 {
		return fmt.Errorf("volume must be between 0.0 and 1.0")
	}

	// Convert to 0-100 scale for server
	volumeInt := int(volume * 100)
	log.Printf("Setting volume to: %.2f (sending %d to server)", volume, volumeInt)

	return p.SendVolumeCommand(volumeInt)
}

// ReadAudioData reads audio data from the player's audio channel
// Blocks until data is available or timeout (10ms). Returns the number of bytes read.
// Returns -1 if the channel is closed (player cleanup).
func (p *Player) ReadAudioData(buffer []byte) int {
	// Check if channel is nil (player cleaned up)
	if p.audioChannel == nil {
		return -1
	}

	select {
	case audioData, ok := <-p.audioChannel:
		if !ok {
			// Channel closed - player is being cleaned up
			return -1
		}
		// Copy audio data to the provided buffer
		n := copy(buffer, audioData)
		if n < len(audioData) {
			log.Printf("Warning: buffer too small, truncated %d bytes", len(audioData)-n)
		}
		return n
	case <-time.After(10 * time.Millisecond):
		// Timeout - no data available, return 0 so Android can retry quickly
		return 0
	}
}

// GetState returns the current player state
func (p *Player) GetState() string {
	p.mu.Lock()
	defer p.mu.Unlock()

	return p.currentState
}

// IsConnected returns whether the player is connected to a server
func (p *Player) IsConnected() bool {
	p.mu.Lock()
	defer p.mu.Unlock()

	return p.isConnected
}

// Cleanup releases all resources
func (p *Player) Cleanup() {
	p.mu.Lock()
	defer p.mu.Unlock()

	log.Printf("Cleaning up player")

	// Stop discovery manager if running
	if p.discoveryMgr != nil {
		p.discoveryMgr.Stop()
		p.discoveryMgr = nil
	}

	// Close WebSocket connection if still open
	if p.conn != nil {
		p.conn.Close()
		p.conn = nil
	}

	// Cancel context to stop all goroutines
	if p.cancel != nil {
		p.cancel()
	}

	// Close audio channel to unblock any readers
	if p.audioChannel != nil {
		close(p.audioChannel)
		p.audioChannel = nil
	}

	p.isRunning = false
	p.isConnected = false
}

// discoverServers runs the mDNS discovery loop
func (p *Player) discoverServers() {
	log.Printf("Starting server discovery for _sendspin-server._tcp")

	// Use custom mDNS discovery to search for _sendspin-server._tcp
	// The resonate-go library is hardcoded to _resonate-server._tcp
	for {
		select {
		case <-p.ctx.Done():
			log.Printf("Discovery cancelled")
			return
		default:
		}

		entries := make(chan *mdns.ServiceEntry, 10)

		go func() {
			for entry := range entries {
				if entry.AddrV4 == nil {
					continue
				}

				address := fmt.Sprintf("%s:%d", entry.AddrV4.String(), entry.Port)
				log.Printf("Discovered SendSpin server: %s at %s", entry.Name, address)

				if p.callback != nil {
					p.callback.OnServerDiscovered(entry.Name, address)
				}
			}
		}()

		params := &mdns.QueryParam{
			Service: "_sendspin-server._tcp",
			Domain:  "local",
			Timeout: 3,
			Entries: entries,
		}

		mdns.Query(params)
		close(entries)

		// Wait before next scan
		select {
		case <-p.ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

// handleProtocolMessages processes incoming protocol messages from the server
func (p *Player) handleProtocolMessages() {
	log.Printf("Starting protocol message handler")

	for {
		// Check if we should stop (connection context canceled)
		select {
		case <-p.connCtx.Done():
			log.Printf("Protocol message handler stopped (context canceled)")
			return
		default:
			// Continue to read messages
		}

		// Read next message - could be text (JSON) or binary (audio/artwork)
		// Note: ReadMessage blocks, so we check context first
		messageType, data, err := p.conn.ReadMessage()
		if err != nil {
			// Check if this was due to context cancellation (expected during disconnect)
			select {
			case <-p.connCtx.Done():
				log.Printf("Protocol message handler stopped (disconnect)")
				return
			default:
				log.Printf("Error reading message: %v", err)
				return
			}
		}

		// Handle based on message type
		if messageType == websocket.TextMessage {
			p.handleTextMessage(data)
		} else if messageType == websocket.BinaryMessage {
			p.handleBinaryMessage(data)
		}
	}
}

// handleTextMessage processes JSON text messages from the server
func (p *Player) handleTextMessage(data []byte) {
	var msg map[string]interface{}
	if err := json.Unmarshal(data, &msg); err != nil {
		log.Printf("Error parsing JSON: %v", err)
		return
	}

	msgType, ok := msg["type"].(string)
	if !ok {
		log.Printf("Message missing type field")
		return
	}

	log.Printf("Received %s message", msgType)

	switch msgType {
	case "stream/start":
		log.Printf("Audio stream started")
		if p.callback != nil {
			p.callback.OnStateChanged("playing")
		}

	case "server/metadata", "server/state":
		// Metadata update from server/metadata or server/state messages
		if payload, ok := msg["payload"].(map[string]interface{}); ok {
			p.handleMetadataPayload(payload)
		}

	case "server/command":
		log.Printf("Server command received")

	case "group/update":
		// Group playback state update (like Python CLI's _handle_group_update)
		if payload, ok := msg["payload"].(map[string]interface{}); ok {
			p.handleGroupUpdate(payload)
		}

	default:
		log.Printf("Unknown message type: %s", msgType)
	}
}

// handleMetadataPayload extracts and reports track metadata
func (p *Player) handleMetadataPayload(payload map[string]interface{}) {
	// Debug: Simple marker to verify code is being called
	log.Printf("DEBUG_V2: handleMetadataPayload called with %d keys", len(payload))

	// Debug: Log the full payload to see the actual structure from server
	payloadJSON, _ := json.Marshal(payload)
	log.Printf("DEBUG_V2: Raw payload: %s", string(payloadJSON))

	// Check for metadata in payload (could be nested or direct)
	metadata := payload
	if meta, ok := payload["metadata"].(map[string]interface{}); ok {
		log.Printf("Found nested metadata object")
		metadata = meta
	}

	// Debug: Log the metadata map we're extracting from
	metadataJSON, _ := json.Marshal(metadata)
	log.Printf("Extracting from metadata: %s", string(metadataJSON))

	title, _ := metadata["title"].(string)
	artist, _ := metadata["artist"].(string)
	album, _ := metadata["album"].(string)
	artworkUrl, _ := metadata["artwork_url"].(string)

	// Duration and position can come as float64 from JSON (seconds)
	var durationMs, positionMs int64
	if dur, ok := metadata["duration"].(float64); ok {
		durationMs = int64(dur * 1000)
	}
	if pos, ok := metadata["position"].(float64); ok {
		positionMs = int64(pos * 1000)
	}

	// Log extracted values AND show what keys were in the payload for debugging
	var keys []string
	for k := range payload {
		keys = append(keys, k)
	}
	log.Printf("Metadata: title=%q artist=%q album=%q artwork=%q | payload_keys=%v", title, artist, album, artworkUrl, keys)
	if p.callback != nil {
		p.callback.OnMetadataUpdate(title, artist, album, artworkUrl, durationMs, positionMs)
	}
}

// handleGroupUpdate processes group/update messages
func (p *Player) handleGroupUpdate(payload map[string]interface{}) {
	// Debug: Show all keys in the group/update payload
	var keys []string
	for k := range payload {
		keys = append(keys, k)
	}
	payloadJSON, _ := json.Marshal(payload)
	log.Printf("Group update payload: keys=%v raw=%s", keys, string(payloadJSON))

	groupId, _ := payload["group_id"].(string)
	groupName, _ := payload["group_name"].(string)
	playbackState := ""
	if ps, ok := payload["playback_state"].(string); ok {
		playbackState = ps
	}

	log.Printf("Group update: id=%s name=%s state=%s", groupId, groupName, playbackState)
	if p.callback != nil {
		p.callback.OnGroupUpdate(groupId, groupName, playbackState)
	}

	// Also extract metadata if present in group/update
	if metadata, ok := payload["metadata"].(map[string]interface{}); ok {
		title, _ := metadata["title"].(string)
		artist, _ := metadata["artist"].(string)
		if title != "" || artist != "" {
			p.handleMetadataPayload(payload)
		}
	}
}

// handleBinaryMessage processes binary messages (audio chunks, artwork)
func (p *Player) handleBinaryMessage(data []byte) {
	const binaryHeaderSize = 9
	if len(data) < binaryHeaderSize {
		log.Printf("Binary message too short: %d bytes", len(data))
		return
	}

	// First byte is message type
	msgType := data[0]
	payload := data[binaryHeaderSize:]

	// Debug: Log all binary messages
	log.Printf("Binary message received: type=%d, total=%d bytes, payload=%d bytes", msgType, len(data), len(payload))

	// Message types 0-7: Audio chunks (slot 0-7)
	// Message types 8-11: Artwork chunks (channel 0-3)
	if msgType >= 8 && msgType <= 11 {
		// Artwork chunk
		log.Printf("Artwork received: %d bytes (channel %d)", len(payload), msgType-8)
		if p.callback != nil {
			p.callback.OnArtwork(payload)
		}
		return
	}

	// Audio chunk (types 0-7) - send to audio channel
	select {
	case p.audioChannel <- payload:
		log.Printf("Audio chunk queued: %d bytes (slot %d)", len(payload), msgType)
	default:
		log.Printf("Audio channel full, dropping %d bytes", len(payload))
	}
}

