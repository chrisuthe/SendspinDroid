package com.sendspindroid.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaAudiobook
import com.sendspindroid.musicassistant.MaAudiobookChapter
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sort options for audiobook chapters.
 */
enum class ChapterSortOption {
    POSITION,       // Chapter order (default)
    POSITION_DESC,  // Reverse chapter order
    NAME,           // Alphabetical
    DURATION        // Shortest first
}

/**
 * UI state for the Audiobook Detail screen.
 */
sealed interface AudiobookDetailUiState {
    data object Loading : AudiobookDetailUiState

    data class Success(
        val audiobook: MaAudiobook,
        val sortOption: ChapterSortOption = ChapterSortOption.POSITION
    ) : AudiobookDetailUiState {
        val sortedChapters: List<MaAudiobookChapter>
            get() = when (sortOption) {
                ChapterSortOption.POSITION -> audiobook.chapters.sortedBy { it.position }
                ChapterSortOption.POSITION_DESC -> audiobook.chapters.sortedByDescending { it.position }
                ChapterSortOption.NAME -> audiobook.chapters.sortedBy { it.name.lowercase() }
                ChapterSortOption.DURATION -> audiobook.chapters.sortedBy { it.duration }
            }

        val chapterCount: Int get() = audiobook.chapters.size

        /** Determine which chapter the resume position falls within. */
        val currentChapterPosition: Int?
            get() {
                val resumeMs = audiobook.resumePositionMs ?: return null
                var accumulatedMs = 0L

                audiobook.chapters.sortedBy { it.position }.forEach { chapter ->
                    val chapterMs = (chapter.duration * 1000).toLong()
                    if (resumeMs >= accumulatedMs && resumeMs < accumulatedMs + chapterMs) {
                        return chapter.position
                    }
                    accumulatedMs += chapterMs
                }
                return null
            }
    }

    data class Error(val message: String) : AudiobookDetailUiState
}

/**
 * ViewModel for the Audiobook Detail screen.
 *
 * Manages loading and displaying audiobook information and chapter listing.
 */
class AudiobookDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AudiobookDetailUiState>(AudiobookDetailUiState.Loading)
    val uiState: StateFlow<AudiobookDetailUiState> = _uiState.asStateFlow()

    private var currentAudiobookId: String? = null

    companion object {
        private const val TAG = "AudiobookDetailVM"
    }

    /**
     * Load audiobook details for the given audiobook ID.
     */
    fun loadAudiobook(audiobookId: String) {
        // Don't reload if same audiobook
        if (audiobookId == currentAudiobookId && _uiState.value is AudiobookDetailUiState.Success) {
            return
        }

        currentAudiobookId = audiobookId
        _uiState.value = AudiobookDetailUiState.Loading

        viewModelScope.launch {
            Log.d(TAG, "Loading audiobook details: $audiobookId")

            MusicAssistantManager.getAudiobook(audiobookId).fold(
                onSuccess = { audiobook ->
                    Log.d(TAG, "Loaded audiobook: ${audiobook.name} (${audiobook.chapters.size} chapters)")
                    _uiState.value = AudiobookDetailUiState.Success(audiobook = audiobook)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load audiobook: $audiobookId", error)
                    _uiState.value = AudiobookDetailUiState.Error(
                        error.message ?: "Failed to load audiobook"
                    )
                }
            )
        }
    }

    /**
     * Update the audiobook metadata (called after navigation provides the name/image).
     */
    fun updateAudiobookInfo(name: String, imageUri: String?, author: String?) {
        val current = _uiState.value
        if (current is AudiobookDetailUiState.Success) {
            _uiState.value = current.copy(
                audiobook = current.audiobook.copy(
                    name = if (name.isNotEmpty()) name else current.audiobook.name,
                    imageUri = imageUri ?: current.audiobook.imageUri,
                    authors = if (author != null) listOf(author) else current.audiobook.authors
                )
            )
        }
    }

    /**
     * Set the sort option for chapters.
     */
    fun setSortOption(sort: ChapterSortOption) {
        val current = _uiState.value
        if (current is AudiobookDetailUiState.Success) {
            _uiState.value = current.copy(sortOption = sort)
        }
    }

    /**
     * Play the audiobook from the beginning (or resume).
     */
    fun playAudiobook() {
        val current = _uiState.value
        if (current !is AudiobookDetailUiState.Success) return

        val uri = current.audiobook.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing audiobook: ${current.audiobook.name}")
            MusicAssistantManager.playMedia(uri, "audiobook").fold(
                onSuccess = {
                    Log.d(TAG, "Started audiobook playback")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play audiobook", error)
                }
            )
        }
    }

    /**
     * Play a specific chapter by playing the audiobook.
     * MA will resume from the last position; chapter-level seeking
     * requires additional server-side API support.
     */
    fun playChapter(chapter: MaAudiobookChapter) {
        val current = _uiState.value
        if (current !is AudiobookDetailUiState.Success) return

        val uri = current.audiobook.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing chapter ${chapter.position}: ${chapter.name}")
            MusicAssistantManager.playMedia(uri, "audiobook").fold(
                onSuccess = {
                    Log.d(TAG, "Started audiobook playback for chapter ${chapter.position}")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play chapter", error)
                }
            )
        }
    }

    /**
     * Add audiobook to queue.
     */
    fun addToQueue() {
        val current = _uiState.value
        if (current !is AudiobookDetailUiState.Success) return

        val uri = current.audiobook.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Adding audiobook to queue: ${current.audiobook.name}")
            MusicAssistantManager.playMedia(uri, "audiobook", enqueue = true).fold(
                onSuccess = {
                    Log.d(TAG, "Audiobook added to queue")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add audiobook to queue", error)
                }
            )
        }
    }

    /**
     * Mark audiobook as fully played.
     */
    fun markPlayed() {
        val current = _uiState.value
        if (current !is AudiobookDetailUiState.Success) return

        val uri = current.audiobook.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Marking audiobook as played: ${current.audiobook.name}")
            MusicAssistantManager.markPlayed(uri).fold(
                onSuccess = {
                    _uiState.value = current.copy(
                        audiobook = current.audiobook.copy(fullyPlayed = true)
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to mark as played", error)
                }
            )
        }
    }

    /**
     * Mark audiobook as not played (reset progress).
     */
    fun markUnplayed() {
        val current = _uiState.value
        if (current !is AudiobookDetailUiState.Success) return

        val uri = current.audiobook.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Marking audiobook as unplayed: ${current.audiobook.name}")
            MusicAssistantManager.markUnplayed(uri).fold(
                onSuccess = {
                    _uiState.value = current.copy(
                        audiobook = current.audiobook.copy(
                            fullyPlayed = false,
                            resumePositionMs = null
                        )
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to mark as unplayed", error)
                }
            )
        }
    }

    /**
     * Refresh audiobook data from the server.
     */
    fun refresh() {
        currentAudiobookId?.let { audiobookId ->
            currentAudiobookId = null // Force reload
            loadAudiobook(audiobookId)
        }
    }
}
