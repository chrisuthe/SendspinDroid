package player

import (
	"log"
	"math"
	"sync"
	"time"
)

// ClockSyncQuality represents the synchronization quality level
type ClockSyncQuality int

const (
	QualityLost ClockSyncQuality = iota
	QualityPoor
	QualityModerate
	QualityGood
	QualityExcellent
)

// KalmanClockSync implements a 2D Kalman filter for clock synchronization.
// It tracks both clock offset and drift rate for accurate audio synchronization.
//
// Key insight: The server uses "loop time" (microseconds since server started),
// not Unix time. We track serverLoopStartUnix = the Unix timestamp when the
// server loop started, so we can convert: local_unix_time = serverLoopStartUnix + server_loop_time
//
// The Kalman state vector is [offset, drift] where offset refines the serverLoopStartUnix estimate.
type KalmanClockSync struct {
	mu sync.RWMutex

	// Server loop origin (Unix microseconds when server loop started)
	serverLoopStartUnix int64
	synced              bool // True after first successful sync

	// Kalman filter state (refines the offset estimate)
	offset         float64 // Refinement to serverLoopStartUnix in microseconds
	drift          float64 // Estimated drift in microseconds per second
	offsetVariance float64 // Uncertainty in offset estimate
	driftVariance  float64 // Uncertainty in drift estimate
	covariance     float64 // Cross-covariance between offset and drift

	// Timing
	lastUpdateTime   int64 // Last measurement time in microseconds
	measurementCount int

	// Configuration (tunable noise parameters)
	processNoiseOffset float64 // How much offset can change per second
	processNoiseDrift  float64 // How much drift rate can change per second
	measurementNoise   float64 // Expected measurement noise (RTT variance)
}

// ClockSyncStatus contains diagnostic information about sync state
type ClockSyncStatus struct {
	OffsetMicroseconds       float64
	DriftMicrosecondsPerSec  float64
	OffsetUncertaintyMicros  float64
	DriftUncertaintyMicros   float64
	MeasurementCount         int
	IsConverged              bool
	IsDriftReliable          bool
	Quality                  ClockSyncQuality
}

// Convergence thresholds
const (
	minMeasurementsForConvergence    = 5
	maxOffsetUncertaintyForConverged = 1000.0 // 1ms uncertainty threshold
	maxDriftUncertaintyForReliable   = 50.0   // 50 μs/s
)

// NewKalmanClockSync creates a new Kalman-based clock synchronizer
func NewKalmanClockSync() *KalmanClockSync {
	cs := &KalmanClockSync{
		processNoiseOffset: 100.0,   // μs²/s - how much offset can drift
		processNoiseDrift:  1.0,     // μs²/s² - how stable is drift rate
		measurementNoise:   10000.0, // μs² - ~3ms std dev from network
	}
	cs.Reset()
	return cs
}

// Reset clears the synchronizer state
func (cs *KalmanClockSync) Reset() {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	cs.serverLoopStartUnix = 0
	cs.synced = false
	cs.offset = 0
	cs.drift = 0
	cs.offsetVariance = 1e12 // Start with 1 second uncertainty
	cs.driftVariance = 1e6   // 1000 μs/s uncertainty
	cs.covariance = 0
	cs.lastUpdateTime = 0
	cs.measurementCount = 0

	log.Printf("KalmanClockSync: Reset")
}

// ProcessMeasurement handles a complete NTP-style time exchange
// t1: client transmit time (Unix µs)
// t2: server receive time (server loop µs) - NOT Unix time!
// t3: server transmit time (server loop µs) - NOT Unix time!
// t4: client receive time (Unix µs)
//
// The server uses "loop time" (microseconds since server started), not Unix time.
// We need to determine when the server started in Unix time to convert.
func (cs *KalmanClockSync) ProcessMeasurement(t1, t2, t3, t4 int64) {
	// Round-trip time: RTT = (T4 - T1) - (T3 - T2)
	rtt := float64((t4 - t1) - (t3 - t2))

	// Discard samples with very high RTT (network congestion)
	if rtt > 100000 { // 100ms
		log.Printf("KalmanClockSync: Discarding high RTT sample: %.0fμs", rtt)
		return
	}

	cs.mu.Lock()
	defer cs.mu.Unlock()

	// First measurement: establish when server loop started in Unix time
	// serverLoopStartUnix = T4 - T3 (approximately, ignoring one-way delay)
	// More accurately: serverLoopStartUnix = T4 - T3 - RTT/2
	if !cs.synced {
		// Use the midpoint estimate: serverLoopStartUnix = (T1 - T2 + T4 - T3) / 2
		// This averages the two estimates to reduce one-way delay error
		estimate1 := t1 - t2 // When we sent, server loop time was T2
		estimate2 := t4 - t3 // When we received, server loop time was T3
		cs.serverLoopStartUnix = (estimate1 + estimate2) / 2

		cs.synced = true
		cs.offset = 0
		cs.lastUpdateTime = t4
		cs.measurementCount = 1
		cs.offsetVariance = 10000 // Start with 10ms uncertainty after first sync

		log.Printf("KalmanClockSync: Synced! serverLoopStartUnix=%d RTT=%.0fμs",
			cs.serverLoopStartUnix, rtt)
		return
	}

	// For subsequent measurements, compute how our estimate differs from this sample
	// New estimate of serverLoopStartUnix from this sample
	newEstimate := (t1 - t2 + t4 - t3) / 2

	// The "measured offset" is how much this sample suggests we should adjust
	measuredOffset := float64(newEstimate - cs.serverLoopStartUnix)

	// Calculate time delta since last update (in seconds)
	dt := float64(t4-cs.lastUpdateTime) / 1_000_000.0
	if dt <= 0 {
		log.Printf("KalmanClockSync: Non-positive dt=%.4fs, skipping", dt)
		return
	}

	// ═══════════════════════════════════════════════════════════════════
	// KALMAN FILTER PREDICT STEP
	// The offset represents accumulated refinement to serverLoopStartUnix
	// ═══════════════════════════════════════════════════════════════════
	predictedOffset := cs.offset + cs.drift*dt
	predictedDrift := cs.drift

	// Predict covariance
	p00 := cs.offsetVariance + 2*cs.covariance*dt + cs.driftVariance*dt*dt + cs.processNoiseOffset*dt
	p01 := cs.covariance + cs.driftVariance*dt
	p11 := cs.driftVariance + cs.processNoiseDrift*dt

	// ═══════════════════════════════════════════════════════════════════
	// KALMAN FILTER UPDATE STEP
	// ═══════════════════════════════════════════════════════════════════

	// Adaptive measurement noise based on RTT
	adaptiveMeasurementNoise := cs.measurementNoise + rtt*rtt/4.0

	// Innovation (measurement residual)
	innovation := measuredOffset - predictedOffset

	// Innovation covariance
	innovationVariance := p00 + adaptiveMeasurementNoise

	// Kalman gain
	k0 := p00 / innovationVariance
	k1 := p01 / innovationVariance

	// Update state estimate
	cs.offset = predictedOffset + k0*innovation
	cs.drift = predictedDrift + k1*innovation

	// Update covariance
	cs.offsetVariance = (1 - k0) * p00
	cs.covariance = (1 - k0) * p01
	cs.driftVariance = p11 - k1*p01

	// Ensure covariance stays positive definite
	if cs.offsetVariance < 0 {
		cs.offsetVariance = 1
	}
	if cs.driftVariance < 0 {
		cs.driftVariance = 0.01
	}

	cs.lastUpdateTime = t4
	cs.measurementCount++

	// Log progress periodically
	if cs.measurementCount <= 10 || cs.measurementCount%10 == 0 {
		log.Printf("KalmanClockSync: #%d offset=%.0fμs (±%.0f) drift=%.2fμs/s RTT=%.0fμs",
			cs.measurementCount,
			cs.offset,
			math.Sqrt(cs.offsetVariance),
			cs.drift,
			rtt)
	}
}

// IsConverged returns whether the filter has enough measurements with low uncertainty
func (cs *KalmanClockSync) IsConverged() bool {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	return cs.measurementCount >= minMeasurementsForConvergence &&
		math.Sqrt(cs.offsetVariance) < maxOffsetUncertaintyForConverged
}

// IsDriftReliable returns whether drift estimate is accurate enough to use
func (cs *KalmanClockSync) IsDriftReliable() bool {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	return cs.measurementCount >= minMeasurementsForConvergence &&
		math.Sqrt(cs.driftVariance) < maxDriftUncertaintyForReliable
}

// ServerToClientTime converts a server loop timestamp to local Unix time
// server_loop_time -> Unix time
func (cs *KalmanClockSync) ServerToClientTime(serverLoopMicros int64) time.Time {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	// If not synced yet, return current time (best guess)
	if !cs.synced {
		return time.Now()
	}

	// Convert server loop time to Unix time:
	// unix_time = serverLoopStartUnix + server_loop_time + offset_refinement
	// Apply drift compensation if we have enough measurements
	offsetRefinement := cs.offset
	if cs.lastUpdateTime > 0 && cs.measurementCount >= minMeasurementsForConvergence {
		now := time.Now().UnixMicro()
		elapsedSeconds := float64(now-cs.lastUpdateTime) / 1_000_000.0
		if math.Sqrt(cs.driftVariance) < maxDriftUncertaintyForReliable {
			offsetRefinement = cs.offset + cs.drift*elapsedSeconds
		}
	}

	unixMicros := cs.serverLoopStartUnix + serverLoopMicros + int64(offsetRefinement)
	return time.UnixMicro(unixMicros)
}

// ClientToServerTime converts a local Unix timestamp to server loop time
func (cs *KalmanClockSync) ClientToServerTime(clientUnixMicros int64) int64 {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	// If not synced, return 0
	if !cs.synced {
		return 0
	}

	// Convert Unix time to server loop time:
	// server_loop_time = unix_time - serverLoopStartUnix - offset_refinement
	return clientUnixMicros - cs.serverLoopStartUnix - int64(cs.offset)
}

// GetStatus returns diagnostic information about sync state
func (cs *KalmanClockSync) GetStatus() ClockSyncStatus {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	offsetUncertainty := math.Sqrt(cs.offsetVariance)
	driftUncertainty := math.Sqrt(cs.driftVariance)

	// Determine quality level
	var quality ClockSyncQuality
	if cs.measurementCount < minMeasurementsForConvergence {
		quality = QualityLost
	} else if offsetUncertainty > 5000 {
		quality = QualityPoor
	} else if offsetUncertainty > 2000 {
		quality = QualityModerate
	} else if offsetUncertainty > 1000 {
		quality = QualityGood
	} else {
		quality = QualityExcellent
	}

	return ClockSyncStatus{
		OffsetMicroseconds:      cs.offset,
		DriftMicrosecondsPerSec: cs.drift,
		OffsetUncertaintyMicros: offsetUncertainty,
		DriftUncertaintyMicros:  driftUncertainty,
		MeasurementCount:        cs.measurementCount,
		IsConverged:             cs.measurementCount >= minMeasurementsForConvergence && offsetUncertainty < maxOffsetUncertaintyForConverged,
		IsDriftReliable:         cs.measurementCount >= minMeasurementsForConvergence && driftUncertainty < maxDriftUncertaintyForReliable,
		Quality:                 quality,
	}
}

// GetOffset returns the current offset estimate in microseconds
func (cs *KalmanClockSync) GetOffset() float64 {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	return cs.offset
}

// GetAdaptiveSyncIntervalMs returns the recommended interval until next sync burst
func (cs *KalmanClockSync) GetAdaptiveSyncIntervalMs() int {
	cs.mu.RLock()
	defer cs.mu.RUnlock()

	// If not enough measurements, sync rapidly
	if cs.measurementCount < 3 {
		return 500 // 500ms between initial bursts
	}

	uncertaintyMs := math.Sqrt(cs.offsetVariance) / 1000.0

	// Adaptive intervals based on sync quality
	if uncertaintyMs < 1.0 {
		return 10000 // Well synced: 10s
	} else if uncertaintyMs < 2.0 {
		return 5000 // Good: 5s
	} else if uncertaintyMs < 5.0 {
		return 2000 // Moderate: 2s
	}
	return 1000 // Poor: 1s
}
