package com.sendspindroid.musicassistant.model

/**
 * Represents a player (speaker) from Music Assistant.
 *
 * Players can be grouped together for multi-room audio. The [canGroupWith] field
 * lists which other players are compatible for grouping with this one, and
 * [groupMembers] shows who is currently in this player's group.
 */
data class MaPlayer(
    val playerId: String,
    val name: String,
    val type: MaPlayerType,
    val provider: String,
    val available: Boolean,
    val powered: Boolean?,
    val playbackState: MaPlaybackState,
    val volumeLevel: Int?,           // 0-100
    val volumeMuted: Boolean?,
    val groupMembers: List<String>,  // IDs of players currently in this player's group
    val canGroupWith: List<String>,  // IDs of players compatible for grouping
    val syncedTo: String?,           // group leader ID (null = standalone or leader)
    val activeGroup: String?,
    val supportedFeatures: Set<MaPlayerFeature>,
    val icon: String,                // MDI icon name (e.g., "mdi-speaker")
    val enabled: Boolean,
    val hideInUi: Boolean
) {
    /** True if this player is the leader of a group (has members, not synced to another). */
    val isGroupLeader: Boolean get() = groupMembers.isNotEmpty() && syncedTo == null

    /** True if this player is synced to another player (a group member, not the leader). */
    val isGroupMember: Boolean get() = syncedTo != null

    /** True if this player supports volume control. */
    val supportsVolume: Boolean get() = MaPlayerFeature.VOLUME_SET in supportedFeatures

    /** True if this player supports group member management. */
    val supportsGrouping: Boolean get() = MaPlayerFeature.SET_MEMBERS in supportedFeatures
}

/**
 * Music Assistant player types.
 */
enum class MaPlayerType {
    PLAYER,
    STEREO_PAIR,
    GROUP,
    PROTOCOL,
    UNKNOWN
}

/**
 * Music Assistant playback states.
 */
enum class MaPlaybackState {
    IDLE,
    PAUSED,
    PLAYING,
    UNKNOWN
}

/**
 * Features that a Music Assistant player may support.
 */
enum class MaPlayerFeature {
    POWER,
    VOLUME_SET,
    VOLUME_MUTE,
    PAUSE,
    SET_MEMBERS,
    SEEK,
    NEXT_PREVIOUS,
    PLAY_ANNOUNCEMENT,
    ENQUEUE,
    SELECT_SOURCE,
    GAPLESS_PLAYBACK,
    PLAY_MEDIA,
    UNKNOWN
}
