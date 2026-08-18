package com.sendspindroid.sendspin.crypto

import android.util.Log
import com.sendspindroid.UserSettings

/**
 * The pairing configuration, persisted in `UserSettings`' sensitive prefs.
 *
 * The Pairing PSK is minted once, on first read, and then never touched again
 * except by [rotatePairingPsk]. There is deliberately no code path here that
 * regenerates or clears it on pairing success, on unpair, or on upgrade:
 * "a successful pairing does not consume or rotate it ... so it can pair the
 * client with any number of servers", and a client that quietly re-minted it
 * would invalidate every pairing token it had ever shown, with no error
 * anywhere - the operator would simply find that pasting the token stopped
 * working.
 */
class AndroidPairingConfigStore : PairingConfigStore {

    override fun load(): PairingConfig {
        val psk = cachedPsk ?: synchronized(lock) {
            // Double-checked: two threads racing first-run generation would
            // each mint a PSK, one would win the write, and the loser's token
            // would be unpairable.
            cachedPsk ?: loadOrMintLocked().also { cachedPsk = it }
        }
        return PairingConfig(
            pairingPsk = psk,
            pairingPskEnabled = UserSettings.getPairingPskEnabled(),
            unpairedAccessEnabled = UserSettings.getUnpairedAccessEnabled(),
        )
    }

    override fun setEnabled(enabled: Boolean) {
        // Only the flag moves. The secret stays, so re-enabling restores every
        // token already in circulation rather than silently invalidating them.
        UserSettings.setPairingPskEnabled(enabled)
    }

    override fun setUnpairedAccess(enabled: Boolean) {
        UserSettings.setUnpairedAccessEnabled(enabled)
    }

    override fun rotatePairingPsk(
        newPsk: ByteArray,
        claimedPskIds: Set<String>,
    ): PairingConfigStore.RotateResult {
        if (newPsk.size != Psk.PSK_SIZE) return PairingConfigStore.RotateResult.Invalid
        if (PskId.derive(newPsk) in claimedPskIds) {
            return PairingConfigStore.RotateResult.AlreadyExists
        }
        synchronized(lock) {
            if (!UserSettings.setPairingPskBlob(Base64Url.encode(newPsk))) {
                Log.e(TAG, "Failed to persist rotated Pairing PSK: no storage available")
                return PairingConfigStore.RotateResult.Invalid
            }
            cachedPsk = newPsk.copyOf()
        }
        return PairingConfigStore.RotateResult.Ok
    }

    private fun loadOrMintLocked(): ByteArray {
        val stored = UserSettings.getPairingPskBlob()
        if (!stored.isNullOrBlank()) {
            val bytes = Base64Url.decodeOrNull(stored)
            if (bytes != null && bytes.size == Psk.PSK_SIZE) return bytes
            // Refuse to silently replace an unreadable secret, for the same
            // reason the identity does: minting a new one would invalidate
            // every token in circulation and report nothing.
            Log.e(
                TAG,
                "Stored Pairing PSK is unreadable. Refusing to overwrite it; " +
                    "pairing is unavailable until it is repaired or deliberately reset."
            )
            error("stored Pairing PSK is corrupt")
        }
        val fresh = secureRandomBytes(Psk.PSK_SIZE)
        if (!UserSettings.setPairingPskBlob(Base64Url.encode(fresh))) {
            Log.e(TAG, "Failed to persist a freshly generated Pairing PSK")
        }
        Log.i(TAG, "Generated a Pairing PSK (psk_id=${PskId.derive(fresh)})")
        return fresh
    }

    internal companion object {
        private const val TAG = "PairingConfig"

        // Process-wide: the PSK is per device, so two store objects must not be
        // able to mint two different ones.
        private val lock = Any()

        @Volatile
        private var cachedPsk: ByteArray? = null

        /**
         * Drop the cached secret. Called by `UserSettings.resetForTesting`, or
         * one test's PSK would answer the next test's "fresh install".
         */
        internal fun resetForTesting() {
            synchronized(lock) { cachedPsk = null }
        }
    }
}
