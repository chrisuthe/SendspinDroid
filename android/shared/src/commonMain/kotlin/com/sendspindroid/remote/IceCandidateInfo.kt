package com.sendspindroid.remote

/**
 * ICE candidate information exchanged via signaling.
 *
 * @property sdp The SDP string of the ICE candidate
 * @property sdpMid The media stream identification tag
 * @property sdpMLineIndex The index of the media description in the SDP
 */
data class IceCandidateInfo(
    val sdp: String,
    val sdpMid: String,
    val sdpMLineIndex: Int
)
