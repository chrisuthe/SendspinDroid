package com.sendspindroid.musicassistant

/**
 * Enqueue mode for playMedia().
 */
enum class EnqueueMode(val apiValue: String?) {
    /** Replace queue and start playing immediately */
    PLAY(null),
    /** Append to end of queue */
    ADD("add"),
    /** Insert after currently playing track */
    NEXT("next"),
    /** Replace queue but don't start playing */
    REPLACE("replace")
}
