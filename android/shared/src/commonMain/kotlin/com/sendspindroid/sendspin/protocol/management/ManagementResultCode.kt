package com.sendspindroid.sendspin.protocol.management

/**
 * The result of a management request.
 *
 * `management.md#client--server-managementresult`. Exactly six codes, pinned by
 * `ManagementRoutingTest`: the server branches on these, so an invented code is
 * one it cannot interpret, and a missing one forces a wrong answer onto a real
 * situation.
 */
enum class ManagementResultCode(val wire: String) {

    /** "operation completed and any state change has been persisted". */
    OK("ok"),

    /** "the request was issued outside a valid management session". */
    PERMISSION_DENIED("permission_denied"),

    /** "the request conflicts with an existing entry on the client". */
    ALREADY_EXISTS("already_exists"),

    /**
     * "the request payload is malformed, contains an out-of-range value, omits
     * a field required for the chosen operation, or violates a referential
     * constraint".
     */
    INVALID("invalid"),

    /** "the request targets an identifier ... that does not exist on the client". */
    NOT_FOUND("not_found"),

    /** "the client cannot persist the change due to full storage". */
    STORAGE_EXHAUSTED("storage_exhausted"),
}
