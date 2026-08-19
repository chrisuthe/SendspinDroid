package com.sendspindroid.sendspin.protocol

/**
 * Why the client is closing the connection.
 *
 * `messaging.md#client--server-clientgoodbye` defines this set, and the server
 * reads it to decide whether to reconnect. That makes a misspelled or invented
 * value worse than sending nothing coherent: the server falls back to the
 * no-goodbye heuristic and reconnects to a client that just asked it not to.
 *
 * An enum rather than string literals so the set can be pinned by a test and so
 * a call site cannot invent a reason - see `GoodbyeReasonTest`.
 */
enum class GoodbyeReason(val wire: String) {

    /** Switching to a different server. */
    ANOTHER_SERVER("another_server"),

    /** The client is shutting down and will not return. */
    SHUTDOWN("shutdown"),

    /** The client is restarting and expects to reconnect. */
    RESTART("restart"),

    /** The user asked to disconnect. */
    USER_REQUEST("user_request"),

    /** The server is not authorised to talk to this client. */
    UNAUTHORIZED("unauthorized"),

    /** The client requires a pairing that has not happened. */
    PAIRING_REQUIRED("pairing_required"),

    /** Another connection attempt superseded this one. */
    CONCURRENT_ATTEMPT("concurrent_attempt"),

    /**
     * The client has processed `server/unpair` from this server.
     *
     * "Server should not auto-reconnect."
     */
    UNPAIRED("unpaired"),
}
