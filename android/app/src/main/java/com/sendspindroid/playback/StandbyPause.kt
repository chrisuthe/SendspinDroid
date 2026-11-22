package com.sendspindroid.playback

/**
 * What a pause means while a TV is in standby.
 *
 * Android TV pauses the active media session when the device goes to sleep,
 * whatever sent it there (timeout, power key, HDMI-CEC). Forwarded as a
 * controller command that pauses every player in the group, though only this
 * one is going away. "To stop taking part in its group's playback" a client
 * sends `client/leave`, so that is sent instead.
 *
 * - Awake: not ours, the pause goes to the server.
 * - Standby, playing: leave. A burst of pauses (four have been seen) leaves once.
 * - Standby, not playing: nothing to pause. A late pause of the burst must not
 *   reach the group that was just left.
 * - A server that predates `client/leave` ignores it: if the same group is
 *   still playing here after [LEAVE_TIMEOUT_MS], pause as before.
 *
 * Called on the main thread only.
 *
 * @param inStandby the device is a TV and not interactive
 * @param playingGroup id of this client's group while it plays, otherwise null
 * @param leaveGroup sends `client/leave`; false if it could not
 */
class StandbyPause(
    private val inStandby: () -> Boolean,
    private val playingGroup: () -> String?,
    private val leaveGroup: () -> Boolean,
    private val pauseGroup: () -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private var leaving = false

    /** @return true if the pause has been dealt with and must not be sent */
    fun handlePause(): Boolean {
        if (!inStandby()) return false
        val group = playingGroup() ?: return true
        if (leaving) return true
        if (!leaveGroup()) return false
        leaving = true
        schedule(LEAVE_TIMEOUT_MS) {
            leaving = false
            if (playingGroup() == group) pauseGroup()
        }
        return true
    }

    companion object {
        /** Long enough for a round trip, short enough to run before a TV suspends. */
        const val LEAVE_TIMEOUT_MS = 1_500L
    }
}
