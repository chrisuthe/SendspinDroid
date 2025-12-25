package player

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"sort"
	"sync"
	"time"

	"github.com/Resonate-Protocol/resonate-go/pkg/audio"
	"github.com/Resonate-Protocol/resonate-go/pkg/discovery"
	"github.com/Resonate-Protocol/resonate-go/pkg/protocol"
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

// burstResult holds timing data from a single time sync exchange
type burstResult struct {
	t1, t2, t3, t4 int64
	rtt            float64
}

// Burst sync configuration (matching C# implementation)
const (
	burstSize       = 8  // Number of messages per burst
	burstIntervalMs = 50 // Milliseconds between burst messages
)

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
	clockSync   *KalmanClockSync
	conn        *websocket.Conn
	connMu      sync.Mutex // Protects WebSocket writes

	// Connection-specific context for canceling the message handler goroutine
	// This is separate from ctx which is for the entire player lifecycle
	connCtx    context.Context
	connCancel context.CancelFunc

	// Burst sync state
	burstMu                sync.Mutex
	burstResults           []burstResult
	pendingBurstTimestamps map[int64]bool

	// Audio state
	currentFormat *audio.Format
	audioChannel  chan []byte    // Legacy simple channel (fallback)
	timedBuffer   *TimedAudioBuffer // Time-synchronized buffer

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

	// Create Kalman-based clock synchronizer
	p.clockSync = NewKalmanClockSync()

	// Create time-synchronized audio buffer
	p.timedBuffer = NewTimedAudioBuffer(p.clockSync)

	// Initialize burst sync state
	p.burstResults = make([]burstResult, 0, burstSize)
	p.pendingBurstTimestamps = make(map[int64]bool)

	// Create connection-specific context for the message handler goroutine
	// This allows us to cancel just this goroutine when disconnecting
	p.connCtx, p.connCancel = context.WithCancel(p.ctx)

	// Start background goroutine for message handling
	go p.handleProtocolMessages()

	// Start time sync loop (burst-based, adaptive intervals)
	go p.timeSyncLoop()

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

	// Clear timed audio buffer
	if p.timedBuffer != nil {
		p.timedBuffer.Clear()
		p.timedBuffer = nil
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

// ReadAudioData reads audio data from the time-synchronized buffer.
// Returns data only when it's time to play (based on server timestamps).
// Returns the number of bytes read, 0 if not ready yet, -1 if player is cleaned up.
func (p *Player) ReadAudioData(buffer []byte) int {
	// Use time-synchronized buffer if available
	if p.timedBuffer != nil {
		n := p.timedBuffer.Read(buffer)
		if n > 0 {
			return n
		}
		// No data ready yet - short sleep to avoid busy waiting
		time.Sleep(5 * time.Millisecond)
		return 0
	}

	// Fallback to legacy channel (shouldn't happen in normal operation)
	if p.audioChannel == nil {
		return -1
	}

	select {
	case audioData, ok := <-p.audioChannel:
		if !ok {
			return -1
		}
		n := copy(buffer, audioData)
		if n < len(audioData) {
			log.Printf("Warning: buffer too small, truncated %d bytes", len(audioData)-n)
		}
		return n
	case <-time.After(10 * time.Millisecond):
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

	// Clear timed audio buffer
	if p.timedBuffer != nil {
		p.timedBuffer.Clear()
		p.timedBuffer = nil
	}

	p.isRunning = false
	p.isConnected = false
}

// GetBufferStats returns the current audio buffer statistics
func (p *Player) GetBufferStats() BufferStats {
	if p.timedBuffer == nil {
		return BufferStats{}
	}
	return p.timedBuffer.GetStats()
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

	case "server/time":
		// Time sync response - process for clock synchronization
		if payload, ok := msg["payload"].(map[string]interface{}); ok {
			p.handleServerTime(payload)
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

	// Check for track change by looking at metadata
	if metadata, ok := payload["metadata"].(map[string]interface{}); ok {
		title, _ := metadata["title"].(string)
		artist, _ := metadata["artist"].(string)

		// If we have new metadata, this could be a track change
		// Clear the audio buffer to start fresh with the new track
		if (title != "" || artist != "") && p.timedBuffer != nil {
			log.Printf("Track change detected (title=%q), clearing audio buffer", title)
			p.timedBuffer.Clear()
		}

		if title != "" || artist != "" {
			p.handleMetadataPayload(payload)
		}
	}

	if p.callback != nil {
		p.callback.OnGroupUpdate(groupId, groupName, playbackState)
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

	// Bytes 1-8: Server timestamp (big-endian int64, microseconds)
	serverTimestamp := int64(binary.BigEndian.Uint64(data[1:9]))

	payload := data[binaryHeaderSize:]

	// Debug: Log binary messages with timestamp
	log.Printf("Binary message: type=%d, serverTime=%d, payload=%d bytes", msgType, serverTimestamp, len(payload))

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

	// Audio chunk (types 0-7)
	// Write to time-synchronized buffer with server timestamp
	if p.timedBuffer != nil {
		p.timedBuffer.Write(payload, serverTimestamp, int(msgType))
	} else {
		// Fallback to simple channel (should not happen in normal operation)
		select {
		case p.audioChannel <- payload:
			log.Printf("Audio chunk queued (fallback): %d bytes (slot %d)", len(payload), msgType)
		default:
			log.Printf("Audio channel full, dropping %d bytes", len(payload))
		}
	}
}

// ============================================================================
// Time Synchronization (Burst-based with Kalman filter)
// ============================================================================

// timeSyncLoop runs the burst time synchronization loop
func (p *Player) timeSyncLoop() {
	log.Printf("TimeSyncLoop: Starting burst time sync loop")

	for {
		select {
		case <-p.connCtx.Done():
			log.Printf("TimeSyncLoop: Stopped (context canceled)")
			return
		default:
		}

		// Check if still connected
		p.mu.Lock()
		connected := p.isConnected
		p.mu.Unlock()

		if !connected {
			log.Printf("TimeSyncLoop: Stopped (disconnected)")
			return
		}

		// Send burst of time sync messages
		p.sendTimeSyncBurst()

		// Get adaptive interval based on sync quality
		intervalMs := p.clockSync.GetAdaptiveSyncIntervalMs()

		status := p.clockSync.GetStatus()
		log.Printf("TimeSyncLoop: Next burst in %dms (offset=%.0fμs, uncertainty=%.0fμs, converged=%v)",
			intervalMs,
			status.OffsetMicroseconds,
			status.OffsetUncertaintyMicros,
			status.IsConverged)

		// Wait for interval
		select {
		case <-p.connCtx.Done():
			return
		case <-time.After(time.Duration(intervalMs) * time.Millisecond):
		}
	}
}

// sendTimeSyncBurst sends a burst of time sync messages
func (p *Player) sendTimeSyncBurst() {
	// Clear previous burst results
	p.burstMu.Lock()
	p.burstResults = p.burstResults[:0]
	p.pendingBurstTimestamps = make(map[int64]bool)
	p.burstMu.Unlock()

	// Send burst of messages
	for i := 0; i < burstSize; i++ {
		select {
		case <-p.connCtx.Done():
			return
		default:
		}

		// Record T1 (client transmit time)
		t1 := time.Now().UnixMicro()

		// Track this timestamp for response matching
		p.burstMu.Lock()
		p.pendingBurstTimestamps[t1] = true
		p.burstMu.Unlock()

		// Send client/time message
		msg := map[string]interface{}{
			"type": "client/time",
			"payload": map[string]interface{}{
				"client_transmitted": t1,
			},
		}

		p.connMu.Lock()
		err := p.conn.WriteJSON(msg)
		p.connMu.Unlock()

		if err != nil {
			log.Printf("TimeSyncLoop: Failed to send time message: %v", err)
			return
		}

		// Wait between burst messages (except after last one)
		if i < burstSize-1 {
			time.Sleep(time.Duration(burstIntervalMs) * time.Millisecond)
		}
	}

	// Wait for responses to arrive
	time.Sleep(time.Duration(burstIntervalMs*2) * time.Millisecond)

	// Process the best result from the burst
	p.processBurstResults()
}

// processBurstResults finds the best RTT measurement and feeds it to Kalman filter
func (p *Player) processBurstResults() {
	p.burstMu.Lock()
	defer p.burstMu.Unlock()

	if len(p.burstResults) == 0 {
		log.Printf("TimeSyncLoop: No burst results to process")
		return
	}

	// Sort by RTT and take the best (lowest RTT = most accurate)
	sort.Slice(p.burstResults, func(i, j int) bool {
		return p.burstResults[i].rtt < p.burstResults[j].rtt
	})

	best := p.burstResults[0]
	log.Printf("TimeSyncLoop: Processing best of %d results: RTT=%.0fμs",
		len(p.burstResults), best.rtt)

	// Feed to Kalman filter
	p.clockSync.ProcessMeasurement(best.t1, best.t2, best.t3, best.t4)

	// Clear results
	p.burstResults = p.burstResults[:0]
	p.pendingBurstTimestamps = make(map[int64]bool)
}

// handleServerTime processes server/time response messages
func (p *Player) handleServerTime(payload map[string]interface{}) {
	// Record T4 (client receive time)
	t4 := time.Now().UnixMicro()

	// Extract timestamps from payload
	// Server sends: client_transmitted (T1), server_received (T2), server_transmitted (T3)
	t1, ok1 := payload["client_transmitted"].(float64)
	t2, ok2 := payload["server_received"].(float64)
	t3, ok3 := payload["server_transmitted"].(float64)

	if !ok1 || !ok2 || !ok3 {
		log.Printf("TimeSyncLoop: Invalid server/time payload: %v", payload)
		return
	}

	t1i := int64(t1)
	t2i := int64(t2)
	t3i := int64(t3)

	// Debug: Log actual timestamp values
	log.Printf("TimeSyncLoop: T1=%d T2=%d T3=%d T4=%d", t1i, t2i, t3i, t4)

	// Calculate RTT: (T4 - T1) - (T3 - T2)
	rtt := float64((t4 - t1i) - (t3i - t2i))

	p.burstMu.Lock()
	defer p.burstMu.Unlock()

	// Check if this is a response to a pending burst message
	if p.pendingBurstTimestamps[t1i] {
		// Collect this result
		p.burstResults = append(p.burstResults, burstResult{
			t1:  t1i,
			t2:  t2i,
			t3:  t3i,
			t4:  t4,
			rtt: rtt,
		})
		delete(p.pendingBurstTimestamps, t1i)
		log.Printf("TimeSyncLoop: Collected burst response RTT=%.0fμs (%d collected)",
			rtt, len(p.burstResults))
	} else {
		// Non-burst response (shouldn't happen normally) - process immediately
		log.Printf("TimeSyncLoop: Processing non-burst response RTT=%.0fμs", rtt)
		p.clockSync.ProcessMeasurement(t1i, t2i, t3i, t4)
	}
}

// GetClockSyncStatus returns the current clock synchronization status
func (p *Player) GetClockSyncStatus() ClockSyncStatus {
	if p.clockSync == nil {
		return ClockSyncStatus{}
	}
	return p.clockSync.GetStatus()
}

