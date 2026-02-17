package com.sendspindroid.musicassistant

import com.sendspindroid.musicassistant.model.MaLibraryItem
import com.sendspindroid.musicassistant.model.MaMediaType
import com.sendspindroid.musicassistant.model.MaPlaybackState
import com.sendspindroid.musicassistant.model.MaPlayer
import com.sendspindroid.musicassistant.model.MaPlayerFeature
import com.sendspindroid.musicassistant.model.MaPlayerType
import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.musicassistant.transport.has
import com.sendspindroid.musicassistant.transport.optBoolean
import com.sendspindroid.musicassistant.transport.optDouble
import com.sendspindroid.musicassistant.transport.optInt
import com.sendspindroid.musicassistant.transport.optJsonArray
import com.sendspindroid.musicassistant.transport.optJsonObject
import com.sendspindroid.musicassistant.transport.optLong
import com.sendspindroid.musicassistant.transport.optString
import com.sendspindroid.shared.log.Log
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.sendspindroid.musicassistant.transport.MaTransportException

/**
 * Shared command client for the Music Assistant API.
 *
 * Encapsulates all MA command methods and JSON parsing logic in a
 * platform-independent class. The transport, API URL, and connection
 * mode are injected by the platform-specific manager.
 *
 * ## Design
 * - All parsing uses `kotlinx.serialization.json.JsonObject/JsonArray`
 * - Image URL extraction is parameterized (no Android dependencies)
 * - Transport is set externally via [setTransport]
 * - Settings accessed through [MaSettingsProvider] interface
 *
 * ## Usage
 * ```kotlin
 * val client = MaCommandClient(settings)
 * client.setTransport(transport, "ws://host:8095/ws", isRemoteMode = false)
 * val players = client.getAllPlayers()
 * ```
 */
class MaCommandClient(private val settings: MaSettingsProvider) {

    companion object {
        private const val TAG = "MaCommandClient"
        private const val COMMAND_TIMEOUT_MS = 15000L

        /**
         * Scheme used for proxying image URLs through the MA API DataChannel.
         * Matches MaProxyImageFetcher.SCHEME on Android.
         */
        const val IMAGE_PROXY_SCHEME = "ma-proxy"
    }

    @Volatile
    private var transport: MaApiTransport? = null

    @Volatile
    private var currentApiUrl: String? = null

    @Volatile
    private var isRemoteMode: Boolean = false

    /**
     * Set the active transport and connection context.
     *
     * @param transport The connected MA API transport, or null to disconnect
     * @param apiUrl The MA API URL (used for image URL construction)
     * @param isRemoteMode Whether connected via WebRTC (affects image proxy URLs)
     */
    fun setTransport(transport: MaApiTransport?, apiUrl: String?, isRemoteMode: Boolean) {
        this.transport = transport
        this.currentApiUrl = apiUrl
        this.isRemoteMode = isRemoteMode
    }

    // ========================================================================
    // Transport Command Sending
    // ========================================================================

    /**
     * Send a command to the MA API via the active transport.
     *
     * @param command The MA command (e.g., "players/all")
     * @param args Command arguments
     * @return The JSON response
     * @throws MaTransportException if transport is not connected
     */
    internal suspend fun sendCommand(
        command: String,
        args: Map<String, Any> = emptyMap()
    ): JsonObject {
        val t = transport ?: throw MaTransportException("MA API transport not connected")
        return t.sendCommand(command, args, COMMAND_TIMEOUT_MS)
    }

    // ========================================================================
    // Player Commands
    // ========================================================================

    /**
     * Fetch all players from Music Assistant.
     */
    suspend fun getAllPlayers(): Result<List<MaPlayer>> {
        return try {
            val response = sendCommand("players/all")
            val players = parsePlayers(response)
            Log.i(TAG, "Fetched ${players.size} players")
            Result.success(players)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch players", e)
            Result.failure(e)
        }
    }

    /**
     * Auto-detect and return the active player ID.
     *
     * Checks stored selection first, then fetches players and picks one.
     *
     * @param serverId The current server ID
     * @return Result with the selected player ID
     */
    suspend fun autoSelectPlayer(serverId: String): Result<String> {
        return try {
            val existingPlayer = settings.getSelectedPlayerForServer(serverId)
            if (existingPlayer != null) {
                Log.d(TAG, "Player already selected: $existingPlayer")
                return Result.success(existingPlayer)
            }

            val response = sendCommand("players/all")
            val playerId = parseActivePlayerId(response)
            if (playerId == null) {
                Log.w(TAG, "No available players found")
                return Result.failure(Exception("No players available"))
            }

            Log.i(TAG, "Auto-selected player: $playerId")
            settings.setSelectedPlayerForServer(serverId, playerId)
            Result.success(playerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-select player", e)
            Result.failure(e)
        }
    }

    /**
     * Resolve the effective queue ID (handles group leader resolution).
     *
     * @param devicePlayerId This device's player ID
     * @return The queue_id to use for queue operations
     */
    suspend fun getEffectiveQueueId(devicePlayerId: String): String {
        return try {
            val response = sendCommand("players/all")
            val players = parsePlayers(response)
            val thisPlayer = players.find { it.playerId == devicePlayerId }

            val effectiveId = thisPlayer?.syncedTo ?: devicePlayerId
            if (effectiveId != devicePlayerId) {
                Log.d(TAG, "Player is grouped — using leader queue: $effectiveId (our ID: $devicePlayerId)")
            }
            effectiveId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve group leader, using own player ID", e)
            devicePlayerId
        }
    }

    /**
     * Set group members for a player.
     */
    suspend fun setGroupMembers(
        targetPlayerId: String,
        playerIdsToAdd: List<String>,
        playerIdsToRemove: List<String>
    ): Result<Unit> {
        return try {
            val args = mutableMapOf<String, Any>("target_player" to targetPlayerId)
            if (playerIdsToAdd.isNotEmpty()) {
                args["player_ids_to_add"] = playerIdsToAdd
            }
            if (playerIdsToRemove.isNotEmpty()) {
                args["player_ids_to_remove"] = playerIdsToRemove
            }
            sendCommand("players/cmd/set_members", args)
            Log.i(TAG, "Group members updated for $targetPlayerId: +${playerIdsToAdd.size} -${playerIdsToRemove.size}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set group members", e)
            Result.failure(e)
        }
    }

    /**
     * Power on a player.
     */
    suspend fun powerOnPlayer(playerId: String): Result<Unit> {
        return try {
            sendCommand("players/cmd/power", mapOf("player_id" to playerId, "powered" to true))
            Log.i(TAG, "Player powered on: $playerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to power on player", e)
            Result.failure(e)
        }
    }

    /**
     * Remove a player from all groups.
     */
    suspend fun ungroupPlayer(playerId: String): Result<Unit> {
        return try {
            sendCommand("players/cmd/ungroup", mapOf("player_id" to playerId))
            Log.i(TAG, "Player ungrouped: $playerId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ungroup player", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Playback Commands
    // ========================================================================

    /**
     * Add the currently playing track to MA favorites.
     */
    suspend fun favoriteCurrentTrack(): Result<String> {
        return try {
            val playersResponse = sendCommand("players/all")
            val currentItemUri = parseCurrentItemUri(playersResponse)
                ?: return Result.failure(Exception("No track currently playing"))

            Log.d(TAG, "Found current track URI: $currentItemUri")
            sendCommand("music/favorites/add_item", mapOf("item" to currentItemUri))
            Log.i(TAG, "Successfully added track to favorites")
            Result.success("Added to favorites")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to favorite track", e)
            Result.failure(e)
        }
    }

    /**
     * Play a media item on the specified queue.
     */
    suspend fun playMedia(
        uri: String,
        queueId: String,
        mediaType: String? = null,
        enqueueMode: EnqueueMode = EnqueueMode.PLAY
    ): Result<Unit> {
        return try {
            Log.d(TAG, "${enqueueMode.name} media: $uri on queue: $queueId")
            val args = mutableMapOf<String, Any>(
                "queue_id" to queueId,
                "media" to uri
            )
            if (mediaType != null) {
                args["media_type"] = mediaType
            }
            enqueueMode.apiValue?.let { args["enqueue"] = it }

            sendCommand("player_queues/play_media", args)
            Log.i(TAG, "Successfully ${enqueueMode.name}: $uri")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ${enqueueMode.name} media: $uri", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Queue Management Commands
    // ========================================================================

    /**
     * Get queue items for a player.
     */
    suspend fun getQueueItems(queueId: String, limit: Int = 200, offset: Int = 0): Result<MaQueueState> {
        return try {
            Log.d(TAG, "Fetching queue items for player: $queueId (limit=$limit, offset=$offset)")
            val queueResponse = sendCommand(
                "player_queues/get",
                mapOf("queue_id" to queueId)
            )
            val itemsResponse = sendCommand(
                "player_queues/items",
                mapOf("queue_id" to queueId, "limit" to limit, "offset" to offset)
            )
            val queueState = parseQueueState(queueResponse, itemsResponse)
            Log.i(TAG, "Fetched ${queueState.items.size} queue items (current index: ${queueState.currentIndex})")
            Result.success(queueState)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch queue items", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all items from the queue.
     */
    suspend fun clearQueue(queueId: String): Result<Unit> {
        return try {
            sendCommand("player_queues/clear", mapOf("queue_id" to queueId))
            Log.i(TAG, "Queue cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear queue", e)
            Result.failure(e)
        }
    }

    /**
     * Jump to and play a specific item in the queue.
     */
    suspend fun playQueueItem(queueId: String, queueItemId: String): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/play_index",
                mapOf("queue_id" to queueId, "index" to queueItemId)
            )
            Log.i(TAG, "Jumped to queue item: $queueItemId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play queue item: $queueItemId", e)
            Result.failure(e)
        }
    }

    /**
     * Remove a specific item from the queue.
     */
    suspend fun removeQueueItem(queueId: String, queueItemId: String): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/delete_item",
                mapOf("queue_id" to queueId, "item_id_or_index" to queueItemId)
            )
            Log.i(TAG, "Removed queue item: $queueItemId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove queue item: $queueItemId", e)
            Result.failure(e)
        }
    }

    /**
     * Move a queue item to a new position.
     */
    suspend fun moveQueueItem(queueId: String, queueItemId: String, posShift: Int): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/move_item",
                mapOf("queue_id" to queueId, "queue_item_id" to queueItemId, "pos_shift" to posShift)
            )
            Log.i(TAG, "Moved queue item: $queueItemId by $posShift")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move queue item: $queueItemId", e)
            Result.failure(e)
        }
    }

    /**
     * Toggle shuffle mode for the queue.
     */
    suspend fun setQueueShuffle(queueId: String, enabled: Boolean): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/shuffle",
                mapOf("queue_id" to queueId, "shuffle_enabled" to enabled)
            )
            Log.i(TAG, "Shuffle set to: $enabled")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set shuffle", e)
            Result.failure(e)
        }
    }

    /**
     * Set repeat mode for the queue.
     *
     * @param mode Repeat mode: "off", "one", or "all"
     */
    suspend fun setQueueRepeat(queueId: String, mode: String): Result<Unit> {
        return try {
            sendCommand(
                "player_queues/repeat",
                mapOf("queue_id" to queueId, "repeat_mode" to mode)
            )
            Log.i(TAG, "Repeat mode set to: $mode")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set repeat mode", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // Library Commands
    // ========================================================================

    /**
     * Get recently played items.
     */
    suspend fun getRecentlyPlayed(limit: Int = 15): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching recently played items (limit=$limit)")
            val response = sendCommand("music/recently_played_items", mapOf("limit" to limit))
            val items = parseMediaItems(response)
            Log.d(TAG, "Got ${items.size} recently played items")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recently played", e)
            Result.failure(e)
        }
    }

    /**
     * Get recently added library items.
     */
    suspend fun getRecentlyAdded(limit: Int = 15): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching recently added items (limit=$limit)")
            val response = sendCommand(
                "music/tracks/library_items",
                mapOf("limit" to limit, "order_by" to "timestamp_added_desc")
            )
            val items = parseMediaItems(response)
            Log.d(TAG, "Got ${items.size} recently added items")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recently added", e)
            Result.failure(e)
        }
    }

    /**
     * Get tracks from the library.
     */
    suspend fun getTracks(limit: Int = 25, offset: Int = 0, orderBy: String = "name"): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching tracks (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/tracks/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val tracks = parseMediaItems(response)
            Log.d(TAG, "Got ${tracks.size} tracks")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch tracks", e)
            Result.failure(e)
        }
    }

    /**
     * Get playlists from the library.
     */
    suspend fun getPlaylists(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaPlaylist>> {
        return try {
            Log.d(TAG, "Fetching playlists (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/playlists/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val playlists = parsePlaylists(response)
            Log.d(TAG, "Got ${playlists.size} playlists")
            Result.success(playlists)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists", e)
            Result.failure(e)
        }
    }

    /**
     * Get a single playlist by ID.
     */
    suspend fun getPlaylist(playlistId: String): Result<MaPlaylist> {
        return try {
            Log.d(TAG, "Fetching playlist: $playlistId")
            val response = sendCommand(
                "music/playlists/get",
                mapOf("item_id" to playlistId, "provider_instance_id_or_domain" to "library")
            )
            val item = response.optJsonObject("result")
                ?: return Result.failure(Exception("Playlist not found"))

            val id = item.optString("item_id")
                .ifEmpty { item.optString("playlist_id") }
                .ifEmpty { return Result.failure(Exception("Playlist has no ID")) }

            val name = item.optString("name")
            if (name.isEmpty()) return Result.failure(Exception("Playlist has no name"))

            val imageUri = extractImageUri(item).ifEmpty { null }
            val trackCount = item.optInt("track_count", 0)
            val owner = item.optString("owner").ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { null }

            val playlist = MaPlaylist(
                playlistId = id,
                name = name,
                imageUri = imageUri,
                trackCount = trackCount,
                owner = owner,
                uri = uri
            )
            Log.d(TAG, "Got playlist: ${playlist.name}")
            Result.success(playlist)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlist: $playlistId", e)
            Result.failure(e)
        }
    }

    /**
     * Get tracks in a playlist.
     */
    suspend fun getPlaylistTracks(playlistId: String): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching playlist tracks for: $playlistId")
            val response = sendCommand(
                "music/playlists/playlist_tracks",
                mapOf("item_id" to playlistId, "provider_instance_id_or_domain" to "library")
            )
            val tracks = parseAlbumTracks(response.optJsonArray("result"))
            Log.d(TAG, "Got ${tracks.size} tracks for playlist $playlistId")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlist tracks: $playlistId", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new playlist.
     */
    suspend fun createPlaylist(name: String): Result<MaPlaylist> {
        return try {
            Log.d(TAG, "Creating playlist: $name")
            val response = sendCommand("music/playlists/create_playlist", mapOf("name" to name))
            val item = response.optJsonObject("result")
                ?: return Result.failure(Exception("Failed to create playlist"))

            val id = item.optString("item_id")
                .ifEmpty { item.optString("playlist_id") }
                .ifEmpty { return Result.failure(Exception("Created playlist has no ID")) }

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { null }

            val playlist = MaPlaylist(
                playlistId = id,
                name = item.optString("name").ifEmpty { name },
                imageUri = imageUri,
                trackCount = 0,
                owner = item.optString("owner").ifEmpty { null },
                uri = uri
            )
            Log.d(TAG, "Created playlist: ${playlist.name} (id=${playlist.playlistId})")
            Result.success(playlist)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create playlist: $name", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a playlist.
     */
    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        return try {
            Log.d(TAG, "Deleting playlist: $playlistId")
            sendCommand(
                "music/playlists/remove",
                mapOf("item_id" to playlistId, "provider_instance_id_or_domain" to "library")
            )
            Log.d(TAG, "Deleted playlist: $playlistId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete playlist: $playlistId", e)
            Result.failure(e)
        }
    }

    /**
     * Add tracks to a playlist.
     */
    suspend fun addPlaylistTracks(playlistId: String, trackUris: List<String>): Result<Unit> {
        return try {
            Log.d(TAG, "Adding ${trackUris.size} tracks to playlist: $playlistId")
            sendCommand(
                "music/playlists/add_playlist_tracks",
                mapOf("db_playlist_id" to playlistId, "uris" to trackUris)
            )
            Log.d(TAG, "Added tracks to playlist: $playlistId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add tracks to playlist: $playlistId", e)
            Result.failure(e)
        }
    }

    /**
     * Remove tracks from a playlist by position.
     */
    suspend fun removePlaylistTracks(playlistId: String, positions: List<Int>): Result<Unit> {
        return try {
            Log.d(TAG, "Removing ${positions.size} tracks from playlist: $playlistId")
            sendCommand(
                "music/playlists/remove_playlist_tracks",
                mapOf("db_playlist_id" to playlistId, "positions_to_remove" to positions)
            )
            Log.d(TAG, "Removed tracks from playlist: $playlistId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove tracks from playlist: $playlistId", e)
            Result.failure(e)
        }
    }

    /**
     * Get albums from the library.
     */
    suspend fun getAlbums(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaAlbum>> {
        return try {
            Log.d(TAG, "Fetching albums (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/albums/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val albums = parseAlbums(response)
            Log.d(TAG, "Got ${albums.size} albums")
            Result.success(albums)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch albums", e)
            Result.failure(e)
        }
    }

    /**
     * Get a single album by ID.
     */
    suspend fun getAlbum(albumId: String): Result<MaAlbum> {
        return try {
            Log.d(TAG, "Fetching album: $albumId")
            val response = sendCommand(
                "music/albums/get",
                mapOf("item_id" to albumId, "provider_instance_id_or_domain" to "library")
            )
            val album = parseAlbumFromResult(response)
                ?: return Result.failure(Exception("Album not found"))
            Log.d(TAG, "Got album: ${album.name}")
            Result.success(album)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch album: $albumId", e)
            Result.failure(e)
        }
    }

    /**
     * Get tracks for an album.
     */
    suspend fun getAlbumTracks(albumId: String): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching album tracks for: $albumId")
            val response = sendCommand(
                "music/albums/album_tracks",
                mapOf(
                    "item_id" to albumId,
                    "provider_instance_id_or_domain" to "library",
                    "in_library_only" to false
                )
            )
            val tracks = parseAlbumTracks(response.optJsonArray("result"))
            Log.d(TAG, "Got ${tracks.size} tracks for album $albumId")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch album tracks: $albumId", e)
            Result.failure(e)
        }
    }

    /**
     * Get artists from the library.
     */
    suspend fun getArtists(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaArtist>> {
        return try {
            Log.d(TAG, "Fetching artists (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/artists/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val artists = parseArtists(response)
            Log.d(TAG, "Got ${artists.size} artists")
            Result.success(artists)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artists", e)
            Result.failure(e)
        }
    }

    /**
     * Get a single artist by ID.
     */
    suspend fun getArtist(artistId: String): Result<MaArtist> {
        return try {
            Log.d(TAG, "Fetching artist: $artistId")
            val response = sendCommand(
                "music/artists/get",
                mapOf("item_id" to artistId, "provider_instance_id_or_domain" to "library")
            )
            val artist = parseArtistFromResult(response)
                ?: return Result.failure(Exception("Artist not found"))
            Log.d(TAG, "Got artist: ${artist.name}")
            Result.success(artist)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artist: $artistId", e)
            Result.failure(e)
        }
    }

    /**
     * Get complete artist details including top tracks and discography.
     */
    suspend fun getArtistDetails(artistId: String): Result<ArtistDetails> {
        return try {
            Log.d(TAG, "Fetching artist details for: $artistId")

            val artistResponse = sendCommand(
                "music/artists/get",
                mapOf("item_id" to artistId, "provider_instance_id_or_domain" to "library")
            )
            val artist = parseArtistFromResult(artistResponse)
                ?: return Result.failure(Exception("Artist not found"))

            val tracksResponse = sendCommand(
                "music/artists/artist_tracks",
                mapOf(
                    "item_id" to artistId,
                    "provider_instance_id_or_domain" to "library",
                    "in_library_only" to false
                )
            )
            val topTracks = parseTracksArray(tracksResponse.optJsonArray("result")).take(10)

            val albumsResponse = sendCommand(
                "music/artists/artist_albums",
                mapOf(
                    "item_id" to artistId,
                    "provider_instance_id_or_domain" to "library",
                    "in_library_only" to false
                )
            )
            val albums = parseAlbumsArray(albumsResponse.optJsonArray("result"))
                .sortedByDescending { it.year ?: 0 }

            Log.d(TAG, "Got artist details: ${artist.name} - ${topTracks.size} top tracks, ${albums.size} albums")
            Result.success(ArtistDetails(artist = artist, topTracks = topTracks, albums = albums))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artist details: $artistId", e)
            Result.failure(e)
        }
    }

    /**
     * Get ALL tracks for an artist (no truncation).
     */
    suspend fun getArtistTracks(artistId: String): Result<List<MaTrack>> {
        return try {
            Log.d(TAG, "Fetching all tracks for artist: $artistId")
            val response = sendCommand(
                "music/artists/artist_tracks",
                mapOf(
                    "item_id" to artistId,
                    "provider_instance_id_or_domain" to "library",
                    "in_library_only" to false
                )
            )
            val tracks = parseTracksArray(response.optJsonArray("result"))
            Log.d(TAG, "Got ${tracks.size} tracks for artist $artistId")
            Result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artist tracks: $artistId", e)
            Result.failure(e)
        }
    }

    /**
     * Get radio stations from the library.
     */
    suspend fun getRadioStations(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaRadio>> {
        return try {
            Log.d(TAG, "Fetching radio stations (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/radios/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val radios = parseRadioStations(response)
            Log.d(TAG, "Got ${radios.size} radio stations")
            Result.success(radios)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch radio stations", e)
            Result.failure(e)
        }
    }

    /**
     * Get podcasts from the library.
     */
    suspend fun getPodcasts(limit: Int = 15, offset: Int = 0, orderBy: String = "name"): Result<List<MaPodcast>> {
        return try {
            Log.d(TAG, "Fetching podcasts (limit=$limit, offset=$offset, orderBy=$orderBy)")
            val response = sendCommand(
                "music/podcasts/library_items",
                mapOf("limit" to limit, "offset" to offset, "order_by" to orderBy)
            )
            val podcasts = parsePodcasts(response)
            Log.d(TAG, "Got ${podcasts.size} podcasts")
            Result.success(podcasts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch podcasts", e)
            Result.failure(e)
        }
    }

    /**
     * Get podcast episodes.
     */
    suspend fun getPodcastEpisodes(podcastId: String): Result<List<MaPodcastEpisode>> {
        return try {
            Log.d(TAG, "Fetching podcast episodes for: $podcastId")
            val response = sendCommand(
                "music/podcasts/podcast_episodes",
                mapOf("item_id" to podcastId, "provider_instance_id_or_domain" to "library")
            )
            val episodes = parsePodcastEpisodes(response)
            Log.d(TAG, "Got ${episodes.size} episodes for podcast $podcastId")
            Result.success(episodes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch podcast episodes: $podcastId", e)
            Result.failure(e)
        }
    }

    /**
     * Browse Music Assistant providers for folder-based content.
     */
    suspend fun browse(path: String? = null): Result<List<MaLibraryItem>> {
        return try {
            Log.d(TAG, "Browsing path: ${path ?: "root"}")
            val args: Map<String, Any> = if (path != null) mapOf("path" to path) else emptyMap()
            val response = sendCommand("music/browse", args)
            val items = parseBrowseItems(response)
            Log.d(TAG, "Got ${items.size} browse items for path: ${path ?: "root"}")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to browse path: ${path ?: "root"}", e)
            Result.failure(e)
        }
    }

    /**
     * Search Music Assistant library.
     */
    suspend fun search(
        query: String,
        mediaTypes: List<MaMediaType>? = null,
        limit: Int = 25,
        libraryOnly: Boolean = true
    ): Result<SearchResults> {
        if (query.length < 2) {
            return Result.failure(Exception("Query too short (minimum 2 characters)"))
        }

        return try {
            Log.d(TAG, "Searching for: '$query' (mediaTypes=$mediaTypes, limit=$limit, libraryOnly=$libraryOnly)")

            val args = mutableMapOf<String, Any>(
                "search_query" to query,
                "limit" to limit,
                "library_only" to libraryOnly
            )

            if (mediaTypes != null && mediaTypes.isNotEmpty()) {
                val typeStrings = mediaTypes.map { type ->
                    when (type) {
                        MaMediaType.TRACK -> "track"
                        MaMediaType.ALBUM -> "album"
                        MaMediaType.ARTIST -> "artist"
                        MaMediaType.PLAYLIST -> "playlist"
                        MaMediaType.RADIO -> "radio"
                        MaMediaType.PODCAST -> "podcast"
                        MaMediaType.FOLDER -> "folder"
                    }
                }
                args["media_types"] = typeStrings
            }

            val response = sendCommand("music/search", args)
            val results = parseSearchResults(response)

            Log.d(TAG, "Search returned ${results.totalCount()} results " +
                    "(${results.artists.size} artists, ${results.albums.size} albums, " +
                    "${results.tracks.size} tracks, ${results.playlists.size} playlists, " +
                    "${results.radios.size} radios, ${results.podcasts.size} podcasts)")

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: '$query'", e)
            Result.failure(e)
        }
    }

    // ========================================================================
    // JSON Parsing — Players
    // ========================================================================

    internal fun parsePlayers(response: JsonObject): List<MaPlayer> {
        val playersArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("players")
            ?: return emptyList()

        return (0 until playersArray.size).mapNotNull { i ->
            (playersArray[i] as? JsonObject)?.let { parsePlayer(it) }
        }
    }

    internal fun parsePlayer(json: JsonObject): MaPlayer {
        val playerId = json.optString("player_id")
            .ifEmpty { json.optString("id") }

        val groupMembersArray = json.optJsonArray("group_members")
        val groupMembers = if (groupMembersArray != null) {
            (0 until groupMembersArray.size).mapNotNull { i ->
                (groupMembersArray[i] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        val canGroupWithArray = json.optJsonArray("can_group_with")
        val canGroupWith = if (canGroupWithArray != null) {
            (0 until canGroupWithArray.size).mapNotNull { i ->
                (canGroupWithArray[i] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        val featuresArray = json.optJsonArray("supported_features")
        val features = if (featuresArray != null) {
            (0 until featuresArray.size).mapNotNull { i ->
                val featureValue = (featuresArray[i] as? JsonPrimitive)?.contentOrNull ?: ""
                if (featureValue.isNotEmpty()) parsePlayerFeature(featureValue) else null
            }.toSet()
        } else {
            emptySet()
        }

        return MaPlayer(
            playerId = playerId,
            name = json.optString("display_name")
                .ifEmpty { json.optString("name").ifEmpty { "Unknown Player" } },
            type = parsePlayerType(json.optString("type").ifEmpty { "unknown" }),
            provider = json.optString("provider"),
            available = json.optBoolean("available", false),
            powered = if (json.has("powered")) json.optBoolean("powered") else null,
            playbackState = parsePlaybackState(
                json.optString("state")
                    .ifEmpty { json.optString("playback_state").ifEmpty { "unknown" } }
            ),
            volumeLevel = if (json.has("volume_level")) json.optInt("volume_level", 0) else null,
            volumeMuted = if (json.has("volume_muted")) json.optBoolean("volume_muted") else null,
            groupMembers = groupMembers,
            canGroupWith = canGroupWith,
            syncedTo = json.optString("synced_to").takeIf { it.isNotEmpty() && it != "null" },
            activeGroup = json.optString("active_group").takeIf { it.isNotEmpty() && it != "null" },
            supportedFeatures = features,
            icon = json.optString("icon").ifEmpty { "mdi-speaker" },
            enabled = json.optBoolean("enabled", true),
            hideInUi = json.optBoolean("hide_in_ui", false)
        )
    }

    internal fun parsePlayerType(value: String): MaPlayerType = when (value.lowercase()) {
        "player" -> MaPlayerType.PLAYER
        "stereo_pair" -> MaPlayerType.STEREO_PAIR
        "group" -> MaPlayerType.GROUP
        "protocol" -> MaPlayerType.PROTOCOL
        else -> MaPlayerType.UNKNOWN
    }

    internal fun parsePlaybackState(value: String): MaPlaybackState = when (value.lowercase()) {
        "idle" -> MaPlaybackState.IDLE
        "paused" -> MaPlaybackState.PAUSED
        "playing" -> MaPlaybackState.PLAYING
        else -> MaPlaybackState.UNKNOWN
    }

    internal fun parsePlayerFeature(value: String): MaPlayerFeature = when (value.lowercase()) {
        "power" -> MaPlayerFeature.POWER
        "volume_set" -> MaPlayerFeature.VOLUME_SET
        "volume_mute" -> MaPlayerFeature.VOLUME_MUTE
        "pause" -> MaPlayerFeature.PAUSE
        "set_members" -> MaPlayerFeature.SET_MEMBERS
        "seek" -> MaPlayerFeature.SEEK
        "next_previous" -> MaPlayerFeature.NEXT_PREVIOUS
        "play_announcement" -> MaPlayerFeature.PLAY_ANNOUNCEMENT
        "enqueue" -> MaPlayerFeature.ENQUEUE
        "select_source" -> MaPlayerFeature.SELECT_SOURCE
        "gapless_playback" -> MaPlayerFeature.GAPLESS_PLAYBACK
        "play_media" -> MaPlayerFeature.PLAY_MEDIA
        else -> MaPlayerFeature.UNKNOWN
    }

    internal fun parseActivePlayerId(response: JsonObject): String? {
        val players = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("players")
            ?: return null

        var firstPlayerId: String? = null

        for (i in 0 until players.size) {
            val player = (players[i] as? JsonObject) ?: continue
            val playerId = player.optString("player_id")
                .ifEmpty { player.optString("id") }
            val state = player.optString("state")
            val available = player.optBoolean("available", true)

            if (playerId.isEmpty() || !available) continue

            if (firstPlayerId == null) {
                firstPlayerId = playerId
            }

            if (state == "playing" || state == "paused") {
                return playerId
            }
        }

        return firstPlayerId
    }

    internal fun parseCurrentItemUri(response: JsonObject): String? {
        val players = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("players")
            ?: return null

        for (i in 0 until players.size) {
            val player = (players[i] as? JsonObject) ?: continue
            val state = player.optString("state")

            if (state == "playing") {
                val currentItem = player.optJsonObject("current_item")
                    ?: player.optJsonObject("current_media")

                if (currentItem != null) {
                    val uri = currentItem.optString("uri")
                    if (uri.isNotBlank()) return uri

                    val mediaItem = currentItem.optJsonObject("media_item")
                    if (mediaItem != null) {
                        val mediaUri = mediaItem.optString("uri")
                        if (mediaUri.isNotBlank()) return mediaUri
                    }
                }
            }
        }

        return null
    }

    // ========================================================================
    // JSON Parsing — Tracks / Media Items
    // ========================================================================

    internal fun parseMediaItems(response: JsonObject): List<MaTrack> {
        val items = mutableListOf<MaTrack>()

        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return items

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue
            val mediaItem = parseMediaItem(item)
            if (mediaItem != null) {
                items.add(mediaItem)
            }
        }

        return items
    }

    internal fun parseMediaItem(json: JsonObject): MaTrack? {
        val mediaType = json.optString("media_type").ifEmpty { "track" }
        if (mediaType != "track") {
            Log.d(TAG, "Skipping non-track item: media_type=$mediaType, name=${json.optString("name")}")
            return null
        }

        val itemId = json.optString("item_id")
            .ifEmpty { json.optString("track_id") }
            .ifEmpty { json.optString("album_id") }
            .ifEmpty { json.optString("uri") }

        if (itemId.isEmpty()) return null

        val name = json.optString("name")
            .ifEmpty { json.optString("title") }

        if (name.isEmpty()) return null

        // Artist can be a string or an object with name
        val artist = extractArtistName(json)

        // Album can be string or object - extract both name and metadata
        val albumObj = json.optJsonObject("album")
        val album = if (albumObj != null) {
            albumObj.optString("name")
        } else {
            // Only use optString for album if it's NOT a json object
            val albumElement = json["album"]
            if (albumElement is JsonPrimitive) albumElement.contentOrNull ?: "" else ""
        }

        val albumId = albumObj?.optString("item_id")?.ifEmpty { null }
            ?: albumObj?.optString("album_id")?.ifEmpty { null }
        val albumType = albumObj?.optString("album_type")?.ifEmpty { null }

        val imageUri = extractImageUri(json)
        val uri = json.optString("uri")
        val duration = json.optLong("duration", 0L).takeIf { it > 0 }

        return MaTrack(
            itemId = itemId,
            name = name,
            artist = artist.ifEmpty { null },
            album = album.ifEmpty { null },
            imageUri = imageUri.ifEmpty { null },
            uri = uri.ifEmpty { null },
            duration = duration,
            albumId = albumId,
            albumType = albumType
        )
    }

    /**
     * Extract artist name from a JSON item. Handles string, object, and array forms.
     */
    private fun extractArtistName(json: JsonObject): String {
        // Check if "artist" is a primitive string
        val artistElement = json["artist"]
        if (artistElement is JsonPrimitive) {
            val direct = artistElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // Check if "artist" is an object with "name"
        val artistObj = json.optJsonObject("artist")
        if (artistObj != null) {
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }

        // Try artists array
        val artists = json.optJsonArray("artists")
        if (artists != null && artists.size > 0) {
            val firstArtist = artists[0] as? JsonObject
            val name = firstArtist?.optString("name") ?: ""
            if (name.isNotEmpty()) return name
        }

        return ""
    }

    internal fun parseTracksArray(array: JsonArray?): List<MaTrack> {
        if (array == null) return emptyList()
        val tracks = mutableListOf<MaTrack>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue
            val track = parseMediaItem(item)
            if (track != null) {
                tracks.add(track)
            }
        }

        return tracks
    }

    internal fun parseAlbumTracks(array: JsonArray?): List<MaTrack> {
        if (array == null) return emptyList()
        val tracks = mutableListOf<MaTrack>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val itemId = item.optString("item_id")
                .ifEmpty { item.optString("track_id") }
                .ifEmpty { item.optString("uri") }

            if (itemId.isEmpty()) continue

            val name = item.optString("name")
                .ifEmpty { item.optString("title") }

            if (name.isEmpty()) continue

            val artist = extractArtistName(item)

            val albumObj = item.optJsonObject("album")
            val album = albumObj?.optString("name")
            val albumId = albumObj?.optString("item_id")?.ifEmpty { null }
                ?: albumObj?.optString("album_id")?.ifEmpty { null }
            val albumType = albumObj?.optString("album_type")?.ifEmpty { null }

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri")
            val duration = item.optLong("duration", 0L).takeIf { it > 0 }

            tracks.add(MaTrack(
                itemId = itemId,
                name = name,
                artist = artist.ifEmpty { null },
                album = album?.ifEmpty { null },
                imageUri = imageUri,
                uri = uri.ifEmpty { null },
                duration = duration,
                albumId = albumId,
                albumType = albumType
            ))
        }

        return tracks
    }

    // ========================================================================
    // JSON Parsing — Albums
    // ========================================================================

    internal fun parseAlbums(response: JsonObject): List<MaAlbum> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parseAlbumsArray(resultArray)
    }

    internal fun parseAlbumsArray(array: JsonArray?): List<MaAlbum> {
        if (array == null) return emptyList()
        val albums = mutableListOf<MaAlbum>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val albumId = item.optString("item_id")
                .ifEmpty { item.optString("album_id") }
                .ifEmpty { item.optString("uri") }

            if (albumId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val artist = extractArtistName(item)

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://album/$albumId" }
            val year = item.optInt("year", 0).takeIf { it > 0 }
            val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
            val albumType = item.optString("album_type").ifEmpty { null }

            albums.add(MaAlbum(
                albumId = albumId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                artist = artist.ifEmpty { null },
                year = year,
                trackCount = trackCount,
                albumType = albumType
            ))
        }

        return albums
    }

    internal fun parseAlbumFromResult(response: JsonObject): MaAlbum? {
        val item = response.optJsonObject("result") ?: return null

        val albumId = item.optString("item_id")
            .ifEmpty { item.optString("album_id") }
            .ifEmpty { return null }

        val name = item.optString("name")
        if (name.isEmpty()) return null

        val artist = extractArtistName(item)

        val imageUri = extractImageUri(item).ifEmpty { null }
        val uri = item.optString("uri").ifEmpty { "library://album/$albumId" }
        val year = item.optInt("year", 0).takeIf { it > 0 }
        val trackCount = item.optInt("track_count", 0).takeIf { it > 0 }
        val albumType = item.optString("album_type").ifEmpty { null }

        return MaAlbum(
            albumId = albumId,
            name = name,
            imageUri = imageUri,
            uri = uri,
            artist = artist.ifEmpty { null },
            year = year,
            trackCount = trackCount,
            albumType = albumType
        )
    }

    // ========================================================================
    // JSON Parsing — Artists
    // ========================================================================

    internal fun parseArtists(response: JsonObject): List<MaArtist> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parseArtistsArray(resultArray)
    }

    internal fun parseArtistsArray(array: JsonArray?): List<MaArtist> {
        if (array == null) return emptyList()
        val artists = mutableListOf<MaArtist>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val artistId = item.optString("item_id")
                .ifEmpty { item.optString("artist_id") }
                .ifEmpty { item.optString("uri") }

            if (artistId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://artist/$artistId" }

            artists.add(MaArtist(
                artistId = artistId,
                name = name,
                imageUri = imageUri,
                uri = uri
            ))
        }

        return artists
    }

    internal fun parseArtistFromResult(response: JsonObject): MaArtist? {
        val item = response.optJsonObject("result") ?: return null

        val artistId = item.optString("item_id")
            .ifEmpty { item.optString("artist_id") }
            .ifEmpty { return null }

        val name = item.optString("name")
        if (name.isEmpty()) return null

        val imageUri = extractImageUri(item).ifEmpty { null }
        val uri = item.optString("uri").ifEmpty { "library://artist/$artistId" }

        return MaArtist(
            artistId = artistId,
            name = name,
            imageUri = imageUri,
            uri = uri
        )
    }

    // ========================================================================
    // JSON Parsing — Playlists
    // ========================================================================

    internal fun parsePlaylists(response: JsonObject): List<MaPlaylist> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parsePlaylistsArray(resultArray)
    }

    internal fun parsePlaylistsArray(array: JsonArray?): List<MaPlaylist> {
        if (array == null) return emptyList()
        val playlists = mutableListOf<MaPlaylist>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val playlistId = item.optString("item_id")
                .ifEmpty { item.optString("playlist_id") }
                .ifEmpty { item.optString("uri") }

            if (playlistId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val trackCount = item.optInt("track_count", 0)
            val owner = item.optString("owner").ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { null }

            playlists.add(MaPlaylist(
                playlistId = playlistId,
                name = name,
                imageUri = imageUri,
                trackCount = trackCount,
                owner = owner,
                uri = uri
            ))
        }

        return playlists
    }

    // ========================================================================
    // JSON Parsing — Radios
    // ========================================================================

    internal fun parseRadioStations(response: JsonObject): List<MaRadio> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parseRadiosArray(resultArray)
    }

    internal fun parseRadiosArray(array: JsonArray?): List<MaRadio> {
        if (array == null) return emptyList()
        val radios = mutableListOf<MaRadio>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val radioId = item.optString("item_id")
                .ifEmpty { item.optString("radio_id") }
                .ifEmpty { item.optString("uri") }

            if (radioId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://radio/$radioId" }
            val provider = item.optString("provider").ifEmpty {
                val mappings = item.optJsonArray("provider_mappings")
                if (mappings != null && mappings.size > 0) {
                    (mappings[0] as? JsonObject)?.optString("provider_domain") ?: ""
                } else ""
            }

            radios.add(MaRadio(
                radioId = radioId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                provider = provider.ifEmpty { null }
            ))
        }

        return radios
    }

    // ========================================================================
    // JSON Parsing — Podcasts
    // ========================================================================

    internal fun parsePodcasts(response: JsonObject): List<MaPodcast> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        return parsePodcastsArray(resultArray)
    }

    internal fun parsePodcastsArray(array: JsonArray?): List<MaPodcast> {
        if (array == null) return emptyList()
        val podcasts = mutableListOf<MaPodcast>()

        for (i in 0 until array.size) {
            val item = (array[i] as? JsonObject) ?: continue

            val podcastId = item.optString("item_id")
                .ifEmpty { item.optString("uri") }

            if (podcastId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { "library://podcast/$podcastId" }
            val publisher = item.optString("publisher").ifEmpty { null }
            val totalEpisodes = item.optInt("total_episodes", 0)

            podcasts.add(MaPodcast(
                podcastId = podcastId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                publisher = publisher,
                totalEpisodes = totalEpisodes
            ))
        }

        return podcasts
    }

    internal fun parsePodcastEpisodes(response: JsonObject): List<MaPodcastEpisode> {
        val resultArray = response.optJsonArray("result")
            ?: response.optJsonObject("result")?.optJsonArray("items")
            ?: return emptyList()

        val episodes = mutableListOf<MaPodcastEpisode>()

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue

            val episodeId = item.optString("item_id")
                .ifEmpty { item.optString("uri") }

            if (episodeId.isEmpty()) continue

            val name = item.optString("name")
            if (name.isEmpty()) continue

            val imageUri = extractImageUri(item).ifEmpty { null }
            val uri = item.optString("uri").ifEmpty { null }
            val position = item.optInt("position", 0)
            val duration = item.optLong("duration", 0)
            val fullyPlayed = item.optBoolean("fully_played", false)
            val resumePositionMs = item.optLong("resume_position_ms", 0)

            episodes.add(MaPodcastEpisode(
                episodeId = episodeId,
                name = name,
                imageUri = imageUri,
                uri = uri,
                position = position,
                duration = duration,
                fullyPlayed = fullyPlayed,
                resumePositionMs = resumePositionMs
            ))
        }

        return episodes
    }

    // ========================================================================
    // JSON Parsing — Search
    // ========================================================================

    internal fun parseSearchResults(response: JsonObject): SearchResults {
        val result = response.optJsonObject("result") ?: return SearchResults()

        return SearchResults(
            artists = parseArtistsArray(result.optJsonArray("artists")),
            albums = parseAlbumsArray(result.optJsonArray("albums")),
            tracks = parseTracksArray(result.optJsonArray("tracks")),
            playlists = parsePlaylistsArray(result.optJsonArray("playlists")),
            radios = parseRadiosArray(result.optJsonArray("radios")),
            podcasts = parsePodcastsArray(result.optJsonArray("podcasts"))
        )
    }

    // ========================================================================
    // JSON Parsing — Browse Items
    // ========================================================================

    internal fun parseBrowseItems(response: JsonObject): List<MaLibraryItem> {
        val items = mutableListOf<MaLibraryItem>()
        val resultArray = response.optJsonArray("result") ?: return items

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue
            val mediaType = item.optString("media_type")
            val name = item.optString("name")

            // Skip ".." back-navigation entries
            if (name == "..") continue

            when (mediaType) {
                "folder" -> {
                    val folderId = item.optString("item_id")
                    val path = item.optString("path")
                    if (folderId.isEmpty() || path.isEmpty()) continue
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val isPlayable = item.optBoolean("is_playable", false)
                    items.add(MaBrowseFolder(
                        folderId = folderId,
                        name = name.ifEmpty { folderId },
                        imageUri = imageUri,
                        uri = uri,
                        path = path,
                        isPlayable = isPlayable
                    ))
                }
                "track" -> {
                    val trackId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (trackId.isEmpty()) continue
                    val artist = extractArtistName(item)
                    val albumName = item.optJsonObject("album")?.optString("name")
                        ?: run {
                            val el = item["album"]
                            if (el is JsonPrimitive) el.contentOrNull ?: "" else ""
                        }
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val duration = item.optLong("duration", 0).takeIf { it > 0 }
                    items.add(MaTrack(
                        itemId = trackId,
                        name = name.ifEmpty { trackId },
                        artist = artist.ifEmpty { null },
                        album = albumName.ifEmpty { null },
                        imageUri = imageUri,
                        uri = uri,
                        duration = duration
                    ))
                }
                "radio" -> {
                    val radioId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (radioId.isEmpty()) continue
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val provider = item.optString("provider").ifEmpty {
                        val mappings = item.optJsonArray("provider_mappings")
                        if (mappings != null && mappings.size > 0) {
                            (mappings[0] as? JsonObject)?.optString("provider_domain") ?: ""
                        } else ""
                    }
                    items.add(MaRadio(
                        radioId = radioId,
                        name = name.ifEmpty { radioId },
                        imageUri = imageUri,
                        uri = uri,
                        provider = provider.ifEmpty { null }
                    ))
                }
                "album" -> {
                    val albumId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (albumId.isEmpty()) continue
                    val artist = extractArtistName(item)
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val year = item.optInt("year", 0).takeIf { it > 0 }
                    items.add(MaAlbum(
                        albumId = albumId,
                        name = name.ifEmpty { albumId },
                        imageUri = imageUri,
                        uri = uri,
                        artist = artist.ifEmpty { null },
                        year = year,
                        trackCount = null,
                        albumType = null
                    ))
                }
                "artist" -> {
                    val artistId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (artistId.isEmpty()) continue
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    items.add(MaArtist(
                        artistId = artistId,
                        name = name.ifEmpty { artistId },
                        imageUri = imageUri,
                        uri = uri
                    ))
                }
                "playlist" -> {
                    val playlistId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (playlistId.isEmpty()) continue
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val trackCount = item.optInt("track_count", 0)
                    val owner = item.optString("owner").ifEmpty { null }
                    items.add(MaPlaylist(
                        playlistId = playlistId,
                        name = name.ifEmpty { playlistId },
                        imageUri = imageUri,
                        trackCount = trackCount,
                        owner = owner,
                        uri = uri
                    ))
                }
                "podcast" -> {
                    val podcastId = item.optString("item_id")
                        .ifEmpty { item.optString("uri") }
                    if (podcastId.isEmpty()) continue
                    val imageUri = extractImageUri(item).ifEmpty { null }
                    val uri = item.optString("uri").ifEmpty { null }
                    val publisher = item.optString("publisher").ifEmpty { null }
                    val totalEpisodes = item.optInt("total_episodes", 0)
                    items.add(MaPodcast(
                        podcastId = podcastId,
                        name = name.ifEmpty { podcastId },
                        imageUri = imageUri,
                        uri = uri,
                        publisher = publisher,
                        totalEpisodes = totalEpisodes
                    ))
                }
            }
        }

        return items
    }

    // ========================================================================
    // JSON Parsing — Queue
    // ========================================================================

    internal fun parseQueueState(queueResponse: JsonObject, itemsResponse: JsonObject): MaQueueState {
        val queueResult = queueResponse.optJsonObject("result") ?: queueResponse
        val shuffleEnabled = queueResult.optBoolean("shuffle_enabled", false)
        val repeatModeRaw = queueResult.optString("repeat_mode").ifEmpty { "off" }
        val repeatMode = when {
            repeatModeRaw.contains("one", ignoreCase = true) -> "one"
            repeatModeRaw.contains("all", ignoreCase = true) -> "all"
            else -> "off"
        }
        val currentIndex = queueResult.optInt("current_index", -1)
        val currentItemId = queueResult.optString("current_item")

        val items = mutableListOf<MaQueueItem>()
        val resultArray = itemsResponse.optJsonArray("result")
            ?: itemsResponse.optJsonObject("result")?.optJsonArray("items")
            ?: JsonArray(emptyList())

        for (i in 0 until resultArray.size) {
            val item = (resultArray[i] as? JsonObject) ?: continue

            val queueItemId = item.optString("queue_item_id")
                .ifEmpty { item.optString("item_id") }
                .ifEmpty { item.optString("id") }

            if (queueItemId.isEmpty()) continue

            val itemName = item.optString("name").ifEmpty {
                item.optJsonObject("media_item")?.optString("name") ?: ""
            }

            val mediaItem = item.optJsonObject("media_item")
            val artist = extractQueueItemArtist(item, mediaItem)
            val album = extractQueueItemAlbum(item, mediaItem)
            val imageUri = extractQueueItemImage(item, mediaItem)
            val duration = item.optLong("duration", 0L).let { if (it > 0) it else null }
            val uri = item.optString("uri")
                .ifEmpty { mediaItem?.optString("uri") ?: "" }
                .ifEmpty { null }

            val isCurrentItem = (i == currentIndex) ||
                (currentItemId.isNotEmpty() && queueItemId == currentItemId)

            items.add(MaQueueItem(
                queueItemId = queueItemId,
                name = itemName.ifEmpty { "Unknown Track" },
                artist = artist,
                album = album,
                imageUri = imageUri,
                duration = duration,
                uri = uri,
                isCurrentItem = isCurrentItem
            ))
        }

        return MaQueueState(
            items = items,
            currentIndex = currentIndex,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }

    /**
     * Extract artist name from a queue item, handling string/object/array forms.
     */
    private fun extractQueueItemArtist(item: JsonObject, mediaItem: JsonObject?): String? {
        // Direct artist field (only if it's a string primitive, not an object/array)
        val artistElement = item["artist"]
        if (artistElement is JsonPrimitive) {
            val direct = artistElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // From media_item
        if (mediaItem != null) {
            val mediaArtistElement = mediaItem["artist"]
            if (mediaArtistElement is JsonPrimitive) {
                val mediaArtist = mediaArtistElement.contentOrNull ?: ""
                if (mediaArtist.isNotEmpty()) return mediaArtist
            }

            // From media_item.artists array
            val artists = mediaItem.optJsonArray("artists")
            if (artists != null && artists.size > 0) {
                val firstArtist = artists[0] as? JsonObject
                val artistName = firstArtist?.optString("name")
                if (!artistName.isNullOrEmpty()) return artistName
            }
        }

        // From item.artists array
        val artists = item.optJsonArray("artists")
        if (artists != null && artists.size > 0) {
            val firstArtist = artists[0] as? JsonObject
            val artistName = firstArtist?.optString("name")
            if (!artistName.isNullOrEmpty()) return artistName
        }

        // artist field might be an object with a name property
        item.optJsonObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }
        mediaItem?.optJsonObject("artist")?.let { artistObj ->
            val name = artistObj.optString("name")
            if (name.isNotEmpty()) return name
        }

        return null
    }

    /**
     * Extract album name from a queue item, handling string/object forms.
     */
    private fun extractQueueItemAlbum(item: JsonObject, mediaItem: JsonObject?): String? {
        // Direct album field (only if it's a string primitive)
        val albumElement = item["album"]
        if (albumElement is JsonPrimitive) {
            val direct = albumElement.contentOrNull ?: ""
            if (direct.isNotEmpty()) return direct
        }

        // From media_item
        if (mediaItem != null) {
            val mediaAlbumElement = mediaItem["album"]
            if (mediaAlbumElement is JsonPrimitive) {
                val mediaAlbum = mediaAlbumElement.contentOrNull ?: ""
                if (mediaAlbum.isNotEmpty()) return mediaAlbum
            }

            val albumObj = mediaItem.optJsonObject("album")
            if (albumObj != null) {
                val albumName = albumObj.optString("name")
                if (albumName.isNotEmpty()) return albumName
            }
        }

        // From item.album object
        val albumObj = item.optJsonObject("album")
        if (albumObj != null) {
            val albumName = albumObj.optString("name")
            if (albumName.isNotEmpty()) return albumName
        }

        return null
    }

    /**
     * Extract image URI from a queue item.
     */
    private fun extractQueueItemImage(item: JsonObject, mediaItem: JsonObject?): String? {
        val itemImage = extractImageUri(item)
        if (itemImage.isNotEmpty()) return itemImage

        if (mediaItem != null) {
            val mediaImage = extractImageUri(mediaItem)
            if (mediaImage.isNotEmpty()) return mediaImage

            val albumObj = mediaItem.optJsonObject("album")
            if (albumObj != null) {
                val albumImage = extractImageUri(albumObj)
                if (albumImage.isNotEmpty()) return albumImage
            }
        }

        val albumObj = item.optJsonObject("album")
        if (albumObj != null) {
            val albumImage = extractImageUri(albumObj)
            if (albumImage.isNotEmpty()) return albumImage
        }

        return null
    }

    // ========================================================================
    // Image URL Extraction
    // ========================================================================

    /**
     * Extract image URI from MA item JSON.
     *
     * Uses the current API URL and remote mode to construct appropriate image URLs.
     * In REMOTE mode, server-local URLs are rewritten to use the proxy scheme.
     */
    internal fun extractImageUri(json: JsonObject): String {
        val apiUrl = currentApiUrl ?: ""
        val remoteMode = isRemoteMode
        val baseUrl = if (remoteMode) {
            "$IMAGE_PROXY_SCHEME://"
        } else {
            apiUrl
                .replace("/ws", "")
                .replace("wss://", "https://")
                .replace("ws://", "http://")
        }

        // Try direct image field - can be a URL string or a JsonObject with path/provider
        val imageField = json["image"]
        when (imageField) {
            is JsonPrimitive -> {
                val imageStr = imageField.contentOrNull ?: ""
                if (imageStr.startsWith("http")) {
                    return maybeProxyImageUrl(imageStr, remoteMode, baseUrl)
                }
            }
            is JsonObject -> {
                val url = buildImageProxyUrl(imageField, baseUrl)
                if (url.isNotEmpty()) return url
            }
            else -> { /* null, JsonArray — skip */ }
        }

        if (baseUrl.isEmpty()) return ""

        // Try metadata.images array
        val metadata = json.optJsonObject("metadata")
        if (metadata != null) {
            val imageUrl = extractImageFromMetadata(metadata, baseUrl)
            if (imageUrl.isNotEmpty()) return imageUrl
        }

        // Try album.image as fallback
        val album = json.optJsonObject("album")
        if (album != null) {
            val albumImageField = album["image"]
            when (albumImageField) {
                is JsonPrimitive -> {
                    val imageStr = albumImageField.contentOrNull ?: ""
                    if (imageStr.startsWith("http")) {
                        return maybeProxyImageUrl(imageStr, remoteMode, baseUrl)
                    }
                }
                is JsonObject -> {
                    val url = buildImageProxyUrl(albumImageField, baseUrl)
                    if (url.isNotEmpty()) return url
                }
                else -> { /* null, JsonArray — skip */ }
            }

            val albumMetadata = album.optJsonObject("metadata")
            if (albumMetadata != null) {
                val imageUrl = extractImageFromMetadata(albumMetadata, baseUrl)
                if (imageUrl.isNotEmpty()) return imageUrl
            }
        }

        return ""
    }

    /**
     * In REMOTE mode, rewrite server-local image URLs to use the proxy scheme.
     */
    private fun maybeProxyImageUrl(url: String, remoteMode: Boolean, baseUrl: String): String {
        if (!remoteMode) return url

        val proxyIndex = url.indexOf("/imageproxy")
        if (proxyIndex >= 0) {
            val pathAndQuery = url.substring(proxyIndex)
            return "$baseUrl$pathAndQuery"
        }

        return url
    }

    /**
     * Build imageproxy URL from an image object with path/provider fields.
     */
    private fun buildImageProxyUrl(imageObj: JsonObject, baseUrl: String): String {
        val path = imageObj.optString("path")
        val provider = imageObj.optString("provider")

        if (path.isEmpty() || baseUrl.isEmpty()) return ""

        if (path.startsWith("http")) {
            val encodedPath = path.encodeURLParameter()
            return "$baseUrl/imageproxy?size=300&fmt=jpeg&path=$encodedPath" +
                    if (provider.isNotEmpty()) "&provider=$provider" else ""
        }

        if (provider.isNotEmpty()) {
            val encodedPath = path.encodeURLParameter()
            return "$baseUrl/imageproxy?provider=$provider&size=300&fmt=jpeg&path=$encodedPath"
        }

        return ""
    }

    /**
     * Extract image URL from metadata.images array.
     */
    private fun extractImageFromMetadata(metadata: JsonObject, baseUrl: String): String {
        val images = metadata.optJsonArray("images")
        if (images == null || images.size == 0) return ""

        for (i in 0 until images.size) {
            val img = (images[i] as? JsonObject) ?: continue
            val imgType = img.optString("type")
            val path = img.optString("path")
            val provider = img.optString("provider")

            if (path.isNotEmpty() && (imgType == "thumb" || i == images.size - 1)) {
                if (path.startsWith("http")) {
                    val encodedPath = path.encodeURLParameter()
                    return "$baseUrl/imageproxy?size=300&fmt=jpeg&path=$encodedPath" +
                            if (provider.isNotEmpty()) "&provider=$provider" else ""
                }

                if (provider.isNotEmpty()) {
                    val encodedPath = path.encodeURLParameter()
                    return "$baseUrl/imageproxy?provider=$provider&size=300&fmt=jpeg&path=$encodedPath"
                }
            }
        }

        return ""
    }
}
