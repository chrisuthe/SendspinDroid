package com.sendspindroid.sendspin.crypto

/**
 * Storage for the pairing configuration.
 *
 * An interface so tests and `:conformance-client` can substitute an in-memory
 * implementation for the Android one.
 */
interface PairingConfigStore {

    sealed interface RotateResult {
        object Ok : RotateResult

        /** The new `psk_id` is already claimed elsewhere in the shared namespace. */
        object AlreadyExists : RotateResult

        /** Not a 32-byte PSK. */
        object Invalid : RotateResult
    }

    /**
     * The current configuration, generating and persisting a Pairing PSK on
     * first use.
     */
    fun load(): PairingConfig

    /** Offer or withdraw the `pairing_psk` method. Never discards the secret. */
    fun setEnabled(enabled: Boolean)

    fun setUnpairedAccess(enabled: Boolean)

    /**
     * Replace the Pairing PSK.
     *
     * The only supported rotation path besides a deliberate local operator
     * action: "The client MUST NOT rotate it on its own". There is no timer, no
     * counter, and no automatic rotation anywhere - pairing success in
     * particular does not consume it.
     *
     * @param claimedPskIds every `psk_id` already spoken for - the trust store's
     *   records and the Sentinel. Passed in rather than read from a store so
     *   this stays testable and so the namespace rule has no second owner.
     */
    fun rotatePairingPsk(newPsk: ByteArray, claimedPskIds: Set<String>): RotateResult
}
