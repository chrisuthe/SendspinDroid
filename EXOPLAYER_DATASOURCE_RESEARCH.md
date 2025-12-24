# ExoPlayer DataSource API: Research & Documentation

## Executive Summary

This document provides a comprehensive technical guide to implementing custom ExoPlayer DataSource implementations for bridging Go player audio to the Media3 ExoPlayer framework. The research covers the DataSource API contract, threading models, buffer management, and integration patterns based on official Android documentation and ExoPlayer source analysis.

---

## 1. Overview of DataSource API

### 1.1 Architecture

The DataSource API is part of the `androidx.media3.datasource` package and provides a flexible interface for loading media data from various sources (network, files, custom protocols, etc.). It follows a **factory pattern** with two key components:

- **DataSource Interface**: Defines the contract for reading data
- **DataSource.Factory Interface**: Creates DataSource instances on-demand

### 1.2 Key Design Principles

1. **Abstraction**: Decouples data loading logic from media playback
2. **Composition**: DataSource can be wrapped/decorated for added functionality
3. **Streaming**: Designed for streaming data (not requiring full content upfront)
4. **Callback-based**: Uses transfer listeners to notify about data flow events
5. **Factory Pattern**: Allows creating multiple DataSource instances as needed

### 1.3 Our Use Case

For GoPlayerDataSource:
- **Source**: Go player's `readAudioData()` method (provides PCM audio frames)
- **Destination**: ExoPlayer's playback pipeline via ProgressiveMediaSource
- **Data Format**: PCM 16-bit stereo audio at 48kHz
- **Stream Type**: Live/streaming (content length unknown, C.LENGTH_UNSET)

---

## 2. DataSource Interface Contract

### 2.1 Core Methods

The DataSource interface defines these required methods:

#### `open(dataSpec: DataSpec): Long`

**Purpose**: Opens a connection to read data from the specified URI.

**Contract**:
- Called once at the beginning of data transfer
- Must return the total content length in bytes
- For live streams or unknown length, return `C.LENGTH_UNSET` (-1L)
- Should call `transferStarted(dataSpec)` to notify listeners
- Throws `IOException` if opening fails

**Parameters**:
- `dataSpec`: Contains URI, position, length, and request headers for the stream

**Return Value**:
- Total content length, or `C.LENGTH_UNSET` for unknown/live streams

**Implementation Notes**:
- Should initialize any resources needed for reading
- Should validate that the URI is accessible
- Should NOT start reading data yet (read() handles that)
- Should throw `IOException` if already opened (prevent double-open)

**For GoPlayerDataSource**:
```kotlin
override fun open(dataSpec: DataSpec): Long {
    if (opened) {
        throw IOException("DataSource already opened")
    }

    // Notify listeners that transfer is starting
    transferStarted(dataSpec)

    opened = true

    // We don't know the stream length (live stream)
    return C.LENGTH_UNSET.toLong()
}
```

---

#### `read(target: ByteArray, offset: Int, length: Int): Int`

**Purpose**: Reads data from the source into a provided buffer.

**Contract**:
- Blocks until at least one byte is available (or end-of-stream)
- Returns the number of bytes actually read
- Returns `C.RESULT_END_OF_INPUT` (-1) when stream is exhausted
- Should call `bytesTransferred(bytesRead)` after successful read
- Throws `IOException` on read errors

**Parameters**:
- `target`: ByteArray to fill with data
- `offset`: Starting position in target array
- `length`: Maximum number of bytes to read

**Return Value**:
- Number of bytes read (>0): Data available
- `C.RESULT_END_OF_INPUT` (-1): Stream ended
- Never returns 0 (must read at least 1 byte or signal end-of-stream)

**Important Behavior**:
- **Blocking**: Must block until data is available (this is by design)
- **Called Frequently**: ExoPlayer calls this in tight loops
- **Buffer Management**: Target array is managed by ExoPlayer; we only fill it
- **Reporting**: Must report bytes transferred via `bytesTransferred()`

**For GoPlayerDataSource**:
```kotlin
override fun read(target: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) {
        return 0  // Special case: zero-length read
    }

    if (!opened) {
        throw IOException("DataSource not opened")
    }

    try {
        // Read from Go player into our buffer
        val bytesRead = goPlayer.readAudioData(buffer).toInt()

        if (bytesRead <= 0) {
            // End of stream (Go player returned no data)
            return C.RESULT_END_OF_INPUT
        }

        // Copy to ExoPlayer's target buffer
        val bytesToCopy = minOf(bytesRead, length)
        System.arraycopy(buffer, 0, target, offset, bytesToCopy)

        // Report bytes transferred
        bytesTransferred(bytesToCopy)

        return bytesToCopy

    } catch (e: Exception) {
        Log.e(TAG, "Error reading from Go player", e)
        throw IOException("Read failed", e)
    }
}
```

---

#### `close()`

**Purpose**: Closes the data source and releases resources.

**Contract**:
- Called when data transfer is complete
- Should clean up any resources (connections, buffers, etc.)
- Should call `transferEnded()` to notify listeners
- Can be called multiple times (should handle gracefully)
- Implementations should be idempotent

**Implementation Notes**:
- Close any connections or file handles
- Release memory buffers
- Cancel any pending operations
- Should NOT throw exceptions

**For GoPlayerDataSource**:
```kotlin
override fun close() {
    if (opened) {
        Log.d(TAG, "Closing GoPlayerDataSource")
        opened = false
        transferEnded()  // Notify listeners
    }
}
```

---

#### `getUri(): Uri?`

**Purpose**: Returns the URI of the source being read.

**Contract**:
- Should return the URI passed to open()
- Returns null if not opened
- Mainly used for debugging and error reporting

**Return Value**:
- URI object representing the source
- null if not opened

**For GoPlayerDataSource**:
```kotlin
override fun getUri(): Uri? {
    return if (opened) {
        Uri.parse("sendspin://stream")
    } else {
        null
    }
}
```

---

### 2.2 Transfer Event Methods (from BaseDataSource)

These are inherited from BaseDataSource and must be called at appropriate times:

#### `transferStarted(dataSpec: DataSpec)`
- Called when data transfer begins
- Notifies all registered TransferListeners
- Allows ExoPlayer to track transfer events

#### `bytesTransferred(byteCount: Int)`
- Called each time data is successfully read
- Reports actual bytes transferred
- Accumulates total bytes transferred for statistics

#### `transferEnded()`
- Called when data transfer ends
- Notifies listeners that the stream is complete

---

## 3. BaseDataSource Abstract Class

### 3.1 Purpose

BaseDataSource is the recommended base class for custom DataSource implementations. It handles:
- Maintaining a list of TransferListeners
- Managing the listener notification lifecycle
- Tracking isNetwork() for bandwidth monitoring

### 3.2 Constructor

```kotlin
class GoPlayerDataSource(
    private val goPlayer: Player_
) : BaseDataSource(
    isNetwork = true  // Tell ExoPlayer this is a network source
) {
```

**isNetwork Parameter**:
- Set to `true` if the DataSource represents a network connection
- Set to `false` for local/file-based sources
- Important for:
  - Bandwidth tracking and statistics
  - Network availability detection
  - Retry policies and error handling
  - Cache policies (network sources may bypass cache)

### 3.3 Why Extend BaseDataSource Instead of Implementing DataSource

| Aspect | BaseDataSource | Direct Implementation |
|--------|----------------|----------------------|
| TransferListener handling | Automatic | Must implement manually |
| Callback notification | Built-in | Must call manually |
| Code reuse | High | Low |
| Complexity | Lower | Higher |
| Flexibility | Sufficient for most cases | Maximum flexibility |

**For GoPlayerDataSource**: Always extend BaseDataSource because we need transfer listener support for ExoPlayer statistics.

---

## 4. DataSource.Factory Interface

### 4.1 Purpose

Factories create DataSource instances on-demand. This allows ExoPlayer to:
- Create new instances for each stream
- Maintain connection pooling and resource management
- Enable parallel requests

### 4.2 Implementation

```kotlin
class GoPlayerDataSourceFactory(
    private val goPlayer: Player_
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return GoPlayerDataSource(goPlayer)
    }
}
```

### 4.3 Key Design Pattern

The factory pattern allows:
1. **Separation of concerns**: Creation logic separate from usage
2. **Reuse**: Same factory used to create multiple instances
3. **Dependency injection**: goPlayer dependency injected at factory creation time

---

## 5. Threading & Synchronization

### 5.1 Threading Model

**ExoPlayer Threading Architecture**:
- ExoPlayer runs on **dedicated playback thread(s)**
- NOT on the main/UI thread
- NOT on arbitrary background threads

**DataSource Behavior**:
- `open()` called on ExoPlayer playback thread
- `read()` called on ExoPlayer playback thread in tight loops
- `close()` called on ExoPlayer playback thread
- **TransferListeners** called on the same playback thread

### 5.2 Blocking Behavior

**Key Insight**: ExoPlayer EXPECTS DataSource.read() to block!

```kotlin
// This is CORRECT behavior:
override fun read(target: ByteArray, offset: Int, length: Int): Int {
    // Block here until data is available from Go player
    val bytesRead = goPlayer.readAudioData(buffer)  // Blocking OK
    // ...
}
```

**Why blocking is safe**:
- `read()` runs on dedicated playback thread (not UI thread)
- Blocking doesn't cause ANR (not on UI thread)
- Blocking is how data pump works: buffering → read blocks → playback continues
- ExoPlayer's load control manages buffering based on blocking behavior

**Important Warning**:
- Do NOT call blocking methods from Go player on UI thread
- Do NOT use `runOnUiThread()` or `runBlocking {}` in DataSource
- Stay on ExoPlayer's playback thread

### 5.3 Synchronization Considerations

**For GoPlayerDataSource**:

1. **Go player thread safety**:
   - Does `Player_.readAudioData()` support concurrent calls?
   - Will it block safely if called from ExoPlayer playback thread?
   - Are there any locks we need to manage?

2. **Our buffer management**:
   - The `buffer` ByteArray is accessed only from read() method
   - No concurrent access (single-threaded on playback thread)
   - No synchronization needed for buffer access

3. **State management**:
   - `opened` flag is read/written only from DataSource methods
   - All called on playback thread
   - No synchronization needed

**Recommendation**:
```kotlin
class GoPlayerDataSource(
    private val goPlayer: Player_
) : BaseDataSource(true) {

    // IMPORTANT: Verify Go player's thread safety documentation
    // Specifically: Is readAudioData() safe to call from multiple threads?

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        // This will be called from ExoPlayer playback thread
        // Safe to call goPlayer.readAudioData() if it's thread-safe
        val bytesRead = goPlayer.readAudioData(buffer)
        // ...
    }
}
```

### 5.4 To Verify

Before finalizing the implementation, verify:
1. Is `Player_.readAudioData()` thread-safe?
2. Does it handle blocking calls correctly?
3. Are there timeout considerations?
4. What happens if called rapidly in succession?

---

## 6. Buffer Management

### 6.1 Buffer Sizes

**ExoPlayer's behavior**:
- Calls `read()` with varying buffer sizes (typically 64KB - 256KB)
- The exact size depends on:
  - Audio format (sample rate, channels, bit depth)
  - ExoPlayer's load control settings
  - Available memory

**For PCM audio at 48kHz, 16-bit, stereo**:
- Byte rate: 48000 samples/sec × 2 bytes × 2 channels = 384 KB/sec
- Typical buffer for 100ms: ~38 KB
- ExoPlayer buffers typically: 100-500ms of audio

### 6.2 Our Buffer Strategy

```kotlin
class GoPlayerDataSource(
    private val goPlayer: Player_
) : BaseDataSource(true) {

    // Internal buffer - size must accommodate Go player's chunk size
    private val buffer = ByteArray(8192)  // 8KB - reasonable for audio chunks

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        // Read from Go player into our fixed buffer
        val bytesRead = goPlayer.readAudioData(buffer)

        // Copy what fits into ExoPlayer's target buffer
        val bytesToCopy = minOf(bytesRead, length)
        System.arraycopy(buffer, 0, target, offset, bytesToCopy)

        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }
}
```

**Key considerations**:
1. **Our buffer size (8192 bytes)**:
   - Large enough to accommodate reasonable Go player chunks
   - Small enough to minimize latency
   - Can be tuned based on Go player API

2. **ExoPlayer's target buffer**:
   - Managed entirely by ExoPlayer
   - We only write to it via System.arraycopy()
   - Must respect the `length` parameter (don't write more)

3. **System.arraycopy()**:
   - Efficient native method for buffer copying
   - Handles bounds checking
   - Preferred over manual byte-by-byte copying

### 6.3 End-of-Stream Signaling

```kotlin
override fun read(target: ByteArray, offset: Int, length: Int): Int {
    val bytesRead = goPlayer.readAudioData(buffer)

    if (bytesRead <= 0) {
        // Go player returned no data - end of stream
        // Return this magic constant to signal end
        return C.RESULT_END_OF_INPUT  // This is -1
    }

    // Normal case: copy and return bytes read
    val bytesToCopy = minOf(bytesRead, length)
    System.arraycopy(buffer, 0, target, offset, bytesToCopy)
    bytesTransferred(bytesToCopy)
    return bytesToCopy
}
```

**Important**: Never return 0 (except for zero-length request). Always return:
- `> 0`: Bytes successfully read
- `C.RESULT_END_OF_INPUT` (-1): Stream ended
- Or throw `IOException`

---

## 7. Integration with MediaSource

### 7.1 Architecture

```
ExoPlayer
    ↓
MediaSource (provides timeline, format, etc.)
    ↓
MediaPeriod (handles actual playback)
    ↓
DataSource (loads raw data bytes)
    ↓
DataSource.Factory (creates DataSource instances)
    ↓
GoPlayerDataSourceFactory
    ↓
GoPlayerDataSource → goPlayer.readAudioData()
```

### 7.2 Using ProgressiveMediaSource (Recommended)

For streaming PCM audio, we can use ExoPlayer's ProgressiveMediaSource:

```kotlin
object GoPlayerMediaSourceFactory {

    fun create(goPlayer: Player_): MediaSource {
        // 1. Create MediaItem with custom URI
        val mediaItem = MediaItem.Builder()
            .setUri("sendspin://stream")
            .setMimeType("audio/raw")  // Indicate raw PCM
            .build()

        // 2. Create DataSourceFactory
        val dataSourceFactory = GoPlayerDataSourceFactory(goPlayer)

        // 3. Use ProgressiveMediaSource with our custom DataSource
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
}
```

**Why ProgressiveMediaSource**:
- Designed for streaming formats (like our PCM stream)
- Handles timeline, buffering, seeking automatically
- Integrates DataSource seamlessly
- Much simpler than implementing custom BaseMediaSource

---

## 8. Key Takeaways

### Critical Points for Implementation

1. **DataSource.read() MUST block** - This is by design and expected
2. **Threading is single-threaded** - ExoPlayer manages thread coordination
3. **Buffer management is straightforward** - Use System.arraycopy()
4. **Error handling is critical** - Proper IOException management required
5. **Use ProgressiveMediaSource** - Avoid custom BaseMediaSource unless necessary

### Questions to Answer Before Implementation

1. Is `Player_.readAudioData()` thread-safe?
2. Does it block or return immediately when no data available?
3. What happens on timeout or disconnection?
4. What is the exact PCM format (sample rate, bit depth, channels)?
5. What size chunks does the Go player return?

---

## 9. References

**Official Documentation:**
- [Media3 ExoPlayer Overview](https://developer.android.com/media/media3/exoplayer)
- [DataSource API Reference](https://developer.android.com/reference/androidx/media3/datasource/DataSource)
- [BaseDataSource API Reference](https://developer.android.com/reference/androidx/media3/datasource/BaseDataSource)
- [ExoPlayer Customization Guide](https://developer.android.com/media/media3/exoplayer/customization)
- [Media Sources Documentation](https://developer.android.com/media/media3/exoplayer/media-sources)

**Community Resources:**
- [Building Custom DataSource for ExoPlayer (Medium)](https://juliensalvi.medium.com/building-custom-datasource-for-exoplayer-87fd16c71950)
- [ExoPlayer GitHub Repository](https://github.com/google/ExoPlayer)
- [Android Media3 Repository](https://github.com/androidx/media)

---

## Conclusion

This research provides the foundational knowledge needed to implement GoPlayerDataSource as a bridge between Go player audio and ExoPlayer. The DataSource API is well-designed and straightforward once you understand the contract, threading model, and buffer management.

With this research complete, Phase 2 implementation (Tasks 2.1-2.6) can proceed with confidence.
