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

// PlayerCallback defines the interface for receiving player events
type PlayerCallback interface {
	OnServerDiscovered(name string, address string)
	OnConnected(serverName string)
	OnDisconnected()
	OnStateChanged(state string)
	OnMetadata(title string, artist string, album string)
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
	hello := map[string]interface{}{
		"type": "client/hello",
		"payload": map[string]interface{}{
			"client_id": p.deviceName,
			"name":      p.deviceName,
			"version":   1,
			"supported_roles": []string{"controller@v1", "player@v1"},
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
				"buffer_capacity":     1048576,
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

	// Wait for server/hello
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
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

	// Close protocol client
	if p.protoClient != nil {
		// Note: Close() doesn't return error in current API
		p.protoClient = nil
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
func (p *Player) ReadAudioData(buffer []byte) int {
	select {
	case audioData := <-p.audioChannel:
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

	if p.cancel != nil {
		p.cancel()
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
		select {
		case <-p.ctx.Done():
			log.Printf("Protocol message handler stopped")
			return

		default:
			// Read next message - could be text (JSON) or binary (audio)
			messageType, data, err := p.conn.ReadMessage()
			if err != nil {
				log.Printf("Error reading message: %v", err)
				return
			}

			// Handle based on message type
			if messageType == websocket.TextMessage {
				// Parse JSON message
				var msg map[string]interface{}
				if err := json.Unmarshal(data, &msg); err != nil {
					log.Printf("Error parsing JSON: %v", err)
					continue
				}

				msgType, ok := msg["type"].(string)
				if !ok {
					log.Printf("Message missing type field")
					continue
				}

				log.Printf("Received %s message", msgType)

				// Handle different message types
				switch msgType {
				case "stream/start":
					// Audio stream is starting
					log.Printf("Audio stream started")
					if p.callback != nil {
						p.callback.OnStateChanged("playing")
					}
				case "server/metadata":
					// Metadata update
					if payload, ok := msg["payload"].(map[string]interface{}); ok {
						title, _ := payload["title"].(string)
						artist, _ := payload["artist"].(string)
						album, _ := payload["album"].(string)
						log.Printf("Metadata: %s by %s from %s", title, artist, album)
						if p.callback != nil {
							p.callback.OnMetadata(title, artist, album)
						}
					}
				case "server/command":
					// Server command (volume, mute)
					log.Printf("Server command received")
				case "group/update":
					// Group playback state update
					log.Printf("Group update received")
				default:
					log.Printf("Unknown message type: %s", msgType)
				}
			} else if messageType == websocket.BinaryMessage {
				// Binary audio chunk with 9-byte header (1 byte type + 8 byte timestamp)
				// Strip the header and send only the PCM audio data
				const binaryHeaderSize = 9
				if len(data) < binaryHeaderSize {
					log.Printf("Binary message too short: %d bytes", len(data))
					continue
				}

				audioData := data[binaryHeaderSize:]

				// Try to send without blocking - drop if channel is full
				select {
				case p.audioChannel <- audioData:
					// Sent successfully
				default:
					// Channel full - drop silently
				}
			}
		}
	}
}

