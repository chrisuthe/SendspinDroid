package com.sendspindroid.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.musicassistant.MaPodcast
import com.sendspindroid.musicassistant.MaPodcastEpisode
import com.sendspindroid.musicassistant.MusicAssistantManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sort options for podcast episodes.
 */
enum class EpisodeSortOption {
    POSITION_DESC,  // Newest first (highest position number)
    POSITION,       // Oldest first (lowest position number)
    NAME,           // Alphabetical
    DURATION        // Shortest first
}

/**
 * UI state for the Podcast Detail screen.
 */
sealed interface PodcastDetailUiState {
    data object Loading : PodcastDetailUiState

    data class Success(
        val podcast: MaPodcast,
        val episodes: List<MaPodcastEpisode>,
        val sortOption: EpisodeSortOption = EpisodeSortOption.POSITION_DESC,
        val totalEpisodesFromMetadata: Int = 0
    ) : PodcastDetailUiState {
        val sortedEpisodes: List<MaPodcastEpisode>
            get() = when (sortOption) {
                EpisodeSortOption.POSITION_DESC -> episodes.sortedByDescending { it.position }
                EpisodeSortOption.POSITION -> episodes.sortedBy { it.position }
                EpisodeSortOption.NAME -> episodes.sortedBy { it.name.lowercase() }
                EpisodeSortOption.DURATION -> episodes.sortedBy { it.duration }
            }

        val episodeCount: Int get() = episodes.size

        /** True total from podcast metadata, or fetched count if metadata unavailable */
        val totalEpisodeCount: Int
            get() = if (totalEpisodesFromMetadata > episodeCount) totalEpisodesFromMetadata else episodeCount

        /** Whether some episodes were not fetched from the server */
        val hasMoreEpisodes: Boolean get() = totalEpisodeCount > episodeCount
    }

    data class Error(val message: String) : PodcastDetailUiState
}

/**
 * ViewModel for the Podcast Detail screen.
 *
 * Manages loading and displaying podcast information and episode listing.
 */
class PodcastDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PodcastDetailUiState>(PodcastDetailUiState.Loading)
    val uiState: StateFlow<PodcastDetailUiState> = _uiState.asStateFlow()

    private var currentPodcastId: String? = null

    companion object {
        private const val TAG = "PodcastDetailVM"
    }

    /**
     * Load podcast details and episodes for the given podcast ID.
     */
    fun loadPodcast(podcastId: String) {
        // Don't reload if same podcast
        if (podcastId == currentPodcastId && _uiState.value is PodcastDetailUiState.Success) {
            return
        }

        currentPodcastId = podcastId
        _uiState.value = PodcastDetailUiState.Loading

        viewModelScope.launch {
            Log.d(TAG, "Loading podcast details: $podcastId")

            val episodesResult = MusicAssistantManager.getPodcastEpisodes(podcastId)

            when {
                episodesResult.isFailure -> {
                    Log.e(TAG, "Failed to load podcast episodes: $podcastId",
                        episodesResult.exceptionOrNull())
                    _uiState.value = PodcastDetailUiState.Error(
                        episodesResult.exceptionOrNull()?.message ?: "Failed to load episodes"
                    )
                }
                else -> {
                    val episodes = episodesResult.getOrThrow()
                    Log.d(TAG, "Loaded ${episodes.size} episodes for podcast $podcastId")

                    // We may not have the full podcast object; create a minimal one from what we know
                    // The podcast name comes from the navigation destination title
                    _uiState.value = PodcastDetailUiState.Success(
                        podcast = MaPodcast(
                            podcastId = podcastId,
                            name = "", // Will be overridden by caller
                            imageUri = episodes.firstOrNull()?.imageUri,
                            uri = "library://podcast/$podcastId",
                            publisher = null,
                            totalEpisodes = episodes.size
                        ),
                        episodes = episodes
                    )
                }
            }
        }
    }

    /**
     * Update the podcast metadata (called after navigation provides the name).
     */
    fun updatePodcastInfo(name: String, imageUri: String?, publisher: String?, totalEpisodes: Int = 0) {
        val current = _uiState.value
        if (current is PodcastDetailUiState.Success) {
            _uiState.value = current.copy(
                podcast = current.podcast.copy(
                    name = name,
                    imageUri = imageUri ?: current.podcast.imageUri,
                    publisher = publisher
                ),
                totalEpisodesFromMetadata = totalEpisodes
            )
        }
    }

    /**
     * Set the sort option for episodes.
     */
    fun setSortOption(sort: EpisodeSortOption) {
        val current = _uiState.value
        if (current is PodcastDetailUiState.Success) {
            _uiState.value = current.copy(sortOption = sort)
        }
    }

    /**
     * Play a specific episode.
     */
    fun playEpisode(episode: MaPodcastEpisode) {
        val uri = episode.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing episode: ${episode.name}")
            MusicAssistantManager.playMedia(uri, "podcast_episode").fold(
                onSuccess = {
                    Log.d(TAG, "Started episode playback")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play episode", error)
                }
            )
        }
    }

    /**
     * Play all episodes of this podcast.
     */
    fun playAll() {
        val current = _uiState.value
        if (current !is PodcastDetailUiState.Success) return

        val uri = current.podcast.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Playing all episodes for podcast: ${current.podcast.name}")
            MusicAssistantManager.playMedia(uri, "podcast").fold(
                onSuccess = {
                    Log.d(TAG, "Started podcast playback")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to play podcast", error)
                }
            )
        }
    }

    /**
     * Add podcast to queue.
     */
    fun addToQueue() {
        val current = _uiState.value
        if (current !is PodcastDetailUiState.Success) return

        val uri = current.podcast.uri ?: return

        viewModelScope.launch {
            Log.d(TAG, "Adding podcast to queue: ${current.podcast.name}")
            MusicAssistantManager.playMedia(uri, "podcast", enqueue = true).fold(
                onSuccess = {
                    Log.d(TAG, "Podcast added to queue")
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to add podcast to queue", error)
                }
            )
        }
    }

    /**
     * Refresh podcast data from the server.
     */
    fun refresh() {
        currentPodcastId?.let { podcastId ->
            currentPodcastId = null // Force reload
            loadPodcast(podcastId)
        }
    }
}
