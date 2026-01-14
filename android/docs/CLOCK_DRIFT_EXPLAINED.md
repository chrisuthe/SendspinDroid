# Clock Rate Error (Drift) Explained

This document explains how clock rate error (drift) is calculated in SpinDroid's time synchronization system, and compares it to the Python reference implementation.

## Overview

SpinDroid synchronizes audio playback across devices by maintaining a model of the time difference between the client (Android device) and the SendSpin server. This involves tracking two quantities:

1. **Clock Offset** - The absolute time difference between clocks (e.g., "the server is 150ms ahead of me")
2. **Clock Drift** - The rate at which the clocks diverge (e.g., "the server clock runs 50ppm faster than mine")

## The Two Clocks

```
Server Clock:    ----+----+----+----+----+----+----+----+----+---->
                     |    |    |    |    |    |    |    |    |
Client Clock:    --+-+--+-+--+-+--+-+--+-+--+-+--+-+--+-+--+-+--->
                    ^
                    Offset (time difference)

If clocks run at different rates, the offset grows over time (drift)
```

## NTP-Style Offset Measurement

SpinDroid uses NTP-style round-trip measurements to estimate clock offset:

```
Client         Server
   |              |
   |---[T1]------>|  T1 = client_transmitted
   |              |
   |      T2      |  T2 = server_received
   |      T3      |  T3 = server_transmitted
   |              |
   |<-----[T4]----|  T4 = client_received
   |              |
```

**Offset calculation:**
```
offset = ((T2 - T1) + (T3 - T4)) / 2
```

This cancels out network latency (assuming symmetric delays) to give the true clock offset.

**Measurement uncertainty:**
```
round_trip_time = (T4 - T1) - (T3 - T2)
max_error = round_trip_time / 2
```

## The 2D Kalman Filter

SpinDroid uses a 2D Kalman filter to track both offset and drift simultaneously.

### State Vector

```
State = [offset, drift]

Where:
- offset = time difference in microseconds (positive = server ahead)
- drift  = rate of change (microseconds per microsecond, dimensionless)
         = roughly equivalent to ppm / 1,000,000
```

### Covariance Matrix

The filter maintains a 2x2 covariance matrix tracking uncertainty:

```
P = | p00  p01 |   where:
    | p10  p11 |

- p00 = variance of offset estimate
- p11 = variance of drift estimate
- p01, p10 = covariance (how correlated the errors are)
```

### Prediction Step

When time `dt` passes between measurements:

```kotlin
// State prediction
offset_predicted = offset + drift * dt
drift_predicted = drift  // Random walk model - drift stays constant

// Covariance prediction
// P_new = F * P * F^T + Q
// Where F = [[1, dt], [0, 1]] and Q = [[q_offset*dt, 0], [0, q_drift*dt]]

p00_new = p00 + 2*p01*dt + p11*dt*dt + PROCESS_NOISE_OFFSET*dt
p01_new = p01 + p11*dt
p10_new = p10 + p11*dt
p11_new = p11 + PROCESS_NOISE_DRIFT*dt
```

### Update Step (Where Drift Gets Updated)

When a new measurement arrives:

```kotlin
// Innovation (measurement residual)
innovation = measurement - offset_predicted

// Kalman gains
s = p00 + measurement_variance  // Innovation covariance
k0 = p00 / s  // Gain for offset
k1 = p10 / s  // Gain for drift  <-- THIS IS THE KEY

// State update
offset = offset_predicted + k0 * innovation
drift = drift + k1 * innovation  // <-- DRIFT CHANGES HERE!

// Stability measures
drift = clamp(drift, -MAX_DRIFT, +MAX_DRIFT)  // ±500 ppm
drift = drift * (1 - DRIFT_DECAY_RATE)         // 1% decay per update
```

**Key insight:** The drift update depends on `k1 = p10 / s`. If the offset-drift covariance (`p10`) is large relative to the innovation variance (`s`), then drift will change significantly with each measurement.

## Time Conversion Formula

When converting server time to client time:

```kotlin
fun serverToClient(serverTimeMicros: Long): Long {
    // Time since the baseline (first measurement)
    val timeSinceBaseline = lastUpdateTime - baselineClientTime

    // Drift correction term
    val driftCorrection = drift * timeSinceBaseline

    // Final conversion
    return serverTimeMicros - offset + driftCorrection + staticDelay
}
```

**Important:** The `drift * timeSinceBaseline` term grows linearly with playback duration. After 10 minutes (600 seconds = 600,000,000 microseconds):

- If drift = 50 ppm = 5e-5: correction = 30,000 µs = **30 ms**
- If drift = 100 ppm = 1e-4: correction = 60,000 µs = **60 ms**

Even small drift estimation errors compound over time.

## Tuning Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `PROCESS_NOISE_OFFSET` | 100.0 µs²/s | Expected clock jitter |
| `PROCESS_NOISE_DRIFT` | 1e-6 (µs/µs)²/s | Expected drift variation |
| `MAX_DRIFT` | ±5e-4 (±500 ppm) | Physically realistic bound |
| `DRIFT_DECAY_RATE` | 0.01 (1%) | Decay towards zero per update |
| Update frequency | 4 Hz | Measurements per second |

## Comparison with Python Reference

**Critical difference found:**

The Python sendspin-cli implementation (`audio.py` line 784):
```python
self._sync_error_filtered_us = self._sync_error_filter.offset  # Only offset!
```

Python only uses the **offset** from the filter. It does NOT use drift for sync corrections.

SpinDroid uses BOTH:
```kotlin
return serverTimeMicros - offset + driftCorrection  // Uses drift!
```

This architectural difference means SpinDroid is more sensitive to drift estimation errors.

## Why Drift May Seem "Too Active"

1. **Every measurement updates drift**: At 4 Hz, that's 4 opportunities per second for drift to change
2. **Drift immediately affects time conversion**: No hysteresis or "drift-active" threshold
3. **Measurement noise propagates to drift**: Network jitter causes offset measurements to vary, which updates drift
4. **Compounding over time**: The `drift * timeSinceBaseline` term amplifies errors

## Potential Improvements

1. **Only use drift when converged**: Wait until filter has high confidence before applying drift correction
2. **Reduce drift process noise**: Lower `PROCESS_NOISE_DRIFT` to make drift more stable
3. **Match Python behavior**: Only use offset for sync corrections, let the feedback loop handle drift naturally
4. **Add drift hysteresis**: Only apply drift correction when it exceeds a threshold

## Debugging Tips

1. **Watch the "Clock Drift" stat**: Should be stable, typically ±10-50 ppm for real devices
2. **Watch drift oscillation**: If drift swings positive/negative rapidly, the filter is chasing noise
3. **Check measurement variance**: Large `max_error` values indicate noisy network conditions
4. **Compare with other clients**: Run Python CLI alongside SpinDroid on same network

## Mathematical Notes

### Why drift affects offset measurements

When clocks run at different rates, the measured offset changes over time:
```
True offset at time T = initial_offset + drift_rate * T
```

The Kalman filter tries to separate these components by observing how offset changes between measurements.

### Kalman gain interpretation

- `k0 = p00 / (p00 + R)`: How much to trust the measurement vs prediction for offset
- `k1 = p10 / (p00 + R)`: How much to update drift based on offset error

If `p10` is large (high correlation between offset and drift errors), then offset errors cause large drift updates.

### Process noise meaning

- `PROCESS_NOISE_OFFSET`: Models random clock jitter (thermal noise, scheduler delays)
- `PROCESS_NOISE_DRIFT`: Models changes to the drift rate itself (temperature changes, crystal aging)

Higher process noise = more trust in measurements = faster adaptation = more noise sensitivity.
