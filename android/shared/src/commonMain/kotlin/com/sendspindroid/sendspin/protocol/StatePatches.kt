package com.sendspindroid.sendspin.protocol

/**
 * A `server/state` delta for the `metadata` role.
 *
 * One [Patch] per leaf. `progress` is a patch of a whole [TrackProgress] rather
 * than a nested patch, because "The merge is shallow: a nested object (e.g.,
 * `metadata.progress`) is replaced or cleared as a whole, never deep-merged, so
 * nested objects are always sent complete."
 */
data class MetadataPatch(
    val timestamp: Patch<Long> = Patch.Absent,
    val title: Patch<String> = Patch.Absent,
    val artist: Patch<String> = Patch.Absent,
    val albumArtist: Patch<String> = Patch.Absent,
    val album: Patch<String> = Patch.Absent,
    val artworkUrl: Patch<String> = Patch.Absent,
    val year: Patch<Int> = Patch.Absent,
    val track: Patch<Int> = Patch.Absent,
    val progress: Patch<TrackProgress> = Patch.Absent,
) : StatePatch<TrackMetadata> {

    override fun applyTo(current: TrackMetadata?): TrackMetadata = TrackMetadata(
        timestamp = timestamp.applyTo(current?.timestamp),
        title = title.applyTo(current?.title),
        artist = artist.applyTo(current?.artist),
        albumArtist = albumArtist.applyTo(current?.albumArtist),
        album = album.applyTo(current?.album),
        artworkUrl = artworkUrl.applyTo(current?.artworkUrl),
        year = year.applyTo(current?.year),
        track = track.applyTo(current?.track),
        progress = progress.applyTo(current?.progress),
    )
}

/** A `server/state` delta for the `controller` role. */
data class ControllerPatch(
    val supportedCommands: Patch<List<String>> = Patch.Absent,
    val volume: Patch<Int> = Patch.Absent,
    val muted: Patch<Boolean> = Patch.Absent,
    val repeat: Patch<String> = Patch.Absent,
    val shuffle: Patch<Boolean> = Patch.Absent,
    val seekMaxMs: Patch<Long> = Patch.Absent,
) : StatePatch<ControllerState> {

    override fun applyTo(current: ControllerState?): ControllerState = ControllerState(
        supportedCommands = supportedCommands.applyTo(current?.supportedCommands),
        volume = volume.applyTo(current?.volume),
        muted = muted.applyTo(current?.muted),
        repeat = repeat.applyTo(current?.repeat),
        shuffle = shuffle.applyTo(current?.shuffle),
        seekMaxMs = seekMaxMs.applyTo(current?.seekMaxMs),
    )
}
