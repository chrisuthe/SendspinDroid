package player

import (
	"log"
	"sync"
	"time"
)

// TimedAudioChunk represents an audio chunk with its scheduled playback time
type TimedAudioChunk struct {
	Data            []byte    // Raw audio data (PCM)
	ServerTimestamp int64     // Server timestamp (microseconds) - when to play
	LocalPlayTime   time.Time // Converted local time when this should play
	Slot            int       // Audio slot (0-7)
}

// TimedAudioBuffer manages audio chunks with time-based release.
// It buffers incoming audio and releases it at the appropriate local time
// based on server timestamps converted through clock synchronization.
type TimedAudioBuffer struct {
	mu        sync.Mutex
	clockSync *KalmanClockSync

	// Pending chunks waiting to be played, sorted by playback time
	chunks []*TimedAudioChunk

	// Current chunk being read from
	currentChunk  *TimedAudioChunk
	currentOffset int

	// Configuration
	targetBufferMs   int   // Target buffer size in milliseconds
	maxBufferMs      int   // Maximum buffer before dropping old chunks
	earlyToleranceUs int64 // How early we can start playing (microseconds)

	// Statistics
	stats BufferStats

	// State
	playbackStarted bool
	transitioning   bool // True during track transitions - output silence to keep ExoPlayer playing
}

// BufferStats contains statistics about buffer performance
type BufferStats struct {
	ChunksBuffered   int
	ChunksDropped    int64
	UnderrunCount    int64
	BytesBuffered    int
	BytesRead        int64
	BytesDropped     int64
	BufferedMs       float64
	IsReadyToPlay    bool
	PlaybackStarted  bool
	CurrentLatencyUs int64 // How far ahead/behind we are
}

// NewTimedAudioBuffer creates a new timed audio buffer
func NewTimedAudioBuffer(clockSync *KalmanClockSync) *TimedAudioBuffer {
	return &TimedAudioBuffer{
		clockSync:        clockSync,
		chunks:           make([]*TimedAudioChunk, 0, 64),
		targetBufferMs:   100,   // 100ms target buffer
		maxBufferMs:      1500,  // 1.5s max - server sends audio ~800ms ahead
		earlyToleranceUs: 50000, // 50ms early tolerance for playback start
	}
}

// Write adds an audio chunk with its server timestamp to the buffer
func (b *TimedAudioBuffer) Write(data []byte, serverTimestamp int64, slot int) {
	if len(data) == 0 {
		return
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	// Convert server timestamp to local playback time
	localTime := b.clockSync.ServerToClientTime(serverTimestamp)

	chunk := &TimedAudioChunk{
		Data:            data,
		ServerTimestamp: serverTimestamp,
		LocalPlayTime:   localTime,
		Slot:            slot,
	}

	// Check buffer capacity - drop oldest if needed
	b.enforceMaxBuffer()

	// Insert chunk in time-sorted order (usually at the end)
	b.insertChunk(chunk)

	// Update stats
	b.stats.BytesBuffered += len(data)
	b.updateBufferStats()

	log.Printf("TimedBuffer: Wrote %d bytes, serverTime=%d, localTime=%v, buffered=%d chunks (%.1fms)",
		len(data), serverTimestamp, localTime.Format("15:04:05.000"), len(b.chunks), b.stats.BufferedMs)
}

// Read reads audio data into the provided buffer.
// Returns the number of bytes read, or 0 if no data is ready yet.
// This is called by the Android audio thread.
func (b *TimedAudioBuffer) Read(buffer []byte) int {
	b.mu.Lock()
	defer b.mu.Unlock()

	// If we have a partial chunk being read, continue with it
	if b.currentChunk != nil {
		n := b.readFromCurrentChunk(buffer)
		if n > 0 && b.stats.UnderrunCount == 0 {
			// Log periodically during normal playback
			if b.stats.BytesRead%(4800*10) < int64(n) {
				log.Printf("TimedBuffer: Reading (chunks=%d, buffered=%.1fms)",
					len(b.chunks), b.stats.BufferedMs)
			}
		}
		return n
	}

	// No chunks buffered - output silence to keep ExoPlayer playing
	// This prevents UI from showing "paused" during track transitions
	if len(b.chunks) == 0 {
		if b.playbackStarted {
			b.stats.UnderrunCount++
			// Log underruns sparingly (every 10th)
			if b.stats.UnderrunCount%10 == 1 {
				log.Printf("TimedBuffer: Underrun #%d - outputting silence", b.stats.UnderrunCount)
			}
			// Output silence to keep playback going
			for i := range buffer {
				buffer[i] = 0
			}
			return len(buffer)
		}
		return 0
	}

	// If we haven't started playback, wait until buffer is ready
	// (80% of target buffer filled - matches C# implementation)
	if !b.playbackStarted {
		if b.stats.BufferedMs < float64(b.targetBufferMs)*0.8 {
			// Not enough buffered yet - output silence if transitioning between tracks
			if b.transitioning {
				for i := range buffer {
					buffer[i] = 0
				}
				return len(buffer)
			}
			return 0
		}

		// Start playback when buffer is sufficiently filled
		b.playbackStarted = true
		b.transitioning = false // End transition
		log.Printf("TimedBuffer: Playback started (buffered=%.1fms, chunks=%d, bytesBuffered=%d)",
			b.stats.BufferedMs, len(b.chunks), b.stats.BytesBuffered)
	}

	// Pop the first chunk and start reading from it
	b.currentChunk = b.chunks[0]
	b.currentOffset = 0
	b.chunks = b.chunks[1:]

	log.Printf("TimedBuffer: Popped chunk (%d bytes), remaining=%d chunks",
		len(b.currentChunk.Data), len(b.chunks))

	return b.readFromCurrentChunk(buffer)
}

// readFromCurrentChunk reads data from the current chunk
func (b *TimedAudioBuffer) readFromCurrentChunk(buffer []byte) int {
	if b.currentChunk == nil {
		return 0
	}

	remaining := len(b.currentChunk.Data) - b.currentOffset
	toCopy := remaining
	if toCopy > len(buffer) {
		toCopy = len(buffer)
	}

	copy(buffer[:toCopy], b.currentChunk.Data[b.currentOffset:b.currentOffset+toCopy])
	b.currentOffset += toCopy
	b.stats.BytesRead += int64(toCopy)
	b.stats.BytesBuffered -= toCopy

	// If we've read the entire chunk, clear it
	if b.currentOffset >= len(b.currentChunk.Data) {
		b.currentChunk = nil
		b.currentOffset = 0
	}

	b.updateBufferStats()
	return toCopy
}

// insertChunk inserts a chunk in time-sorted order
func (b *TimedAudioBuffer) insertChunk(chunk *TimedAudioChunk) {
	// Fast path: chunk goes at the end (most common case)
	if len(b.chunks) == 0 || !chunk.LocalPlayTime.Before(b.chunks[len(b.chunks)-1].LocalPlayTime) {
		b.chunks = append(b.chunks, chunk)
		return
	}

	// Find insertion point (binary search would be overkill for small buffers)
	insertAt := len(b.chunks)
	for i, c := range b.chunks {
		if chunk.LocalPlayTime.Before(c.LocalPlayTime) {
			insertAt = i
			break
		}
	}

	// Insert at position
	b.chunks = append(b.chunks, nil)
	copy(b.chunks[insertAt+1:], b.chunks[insertAt:])
	b.chunks[insertAt] = chunk
}

// enforceMaxBuffer drops oldest chunks if buffer exceeds maximum
func (b *TimedAudioBuffer) enforceMaxBuffer() {
	// Calculate current buffer duration based on audio format (48kHz, 16-bit stereo = 192000 bytes/sec)
	const bytesPerSecond = 48000 * 2 * 2 // 48kHz * 2 channels * 2 bytes per sample
	maxBytes := (b.maxBufferMs * bytesPerSecond) / 1000

	for b.stats.BytesBuffered > maxBytes && len(b.chunks) > 0 {
		dropped := b.chunks[0]
		b.chunks = b.chunks[1:]
		b.stats.ChunksDropped++
		b.stats.BytesDropped += int64(len(dropped.Data))
		b.stats.BytesBuffered -= len(dropped.Data)
		log.Printf("TimedBuffer: Dropped chunk (%d bytes) - buffer overflow", len(dropped.Data))
	}
}

// updateBufferStats updates the buffered milliseconds calculation
func (b *TimedAudioBuffer) updateBufferStats() {
	const bytesPerMs = (48000 * 2 * 2) / 1000 // 192 bytes per ms for 48kHz stereo 16-bit
	b.stats.BufferedMs = float64(b.stats.BytesBuffered) / float64(bytesPerMs)
	b.stats.ChunksBuffered = len(b.chunks)
	b.stats.IsReadyToPlay = b.stats.BufferedMs >= float64(b.targetBufferMs)*0.8
	b.stats.PlaybackStarted = b.playbackStarted
}

// GetStats returns current buffer statistics
func (b *TimedAudioBuffer) GetStats() BufferStats {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.stats
}

// Clear resets the buffer
func (b *TimedAudioBuffer) Clear() {
	b.mu.Lock()
	defer b.mu.Unlock()

	// Always set transitioning so we output silence during buffer refill
	// This keeps ExoPlayer in playing state even when navigating to
	// tracks that haven't started buffering yet
	b.transitioning = true
	log.Printf("TimedBuffer: Transitioning (track change, wasPlaying=%v)", b.playbackStarted)

	b.chunks = b.chunks[:0]
	b.currentChunk = nil
	b.currentOffset = 0
	b.playbackStarted = false
	b.stats = BufferStats{}

	log.Printf("TimedBuffer: Cleared")
}

// IsReadyForPlayback returns whether enough audio is buffered to start playback
func (b *TimedAudioBuffer) IsReadyForPlayback() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.stats.IsReadyToPlay
}

// SetTargetBuffer sets the target buffer duration in milliseconds
func (b *TimedAudioBuffer) SetTargetBuffer(ms int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.targetBufferMs = ms
}

// SetMaxBuffer sets the maximum buffer duration in milliseconds
func (b *TimedAudioBuffer) SetMaxBuffer(ms int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.maxBufferMs = ms
}
