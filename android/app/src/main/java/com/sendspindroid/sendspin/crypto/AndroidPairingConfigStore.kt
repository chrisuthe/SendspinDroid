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
            recordModePskId = recordModePskId(),
        )
    }

    override fun setEnabled(enabled: Boolean): Boolean {
        // Only the flag moves. The secret stays, so re-enabling restores every
        // token already in circulation rather than silently invalidating them.
        return UserSettings.setPairingPskEnabled(enabled)
    }

    override fun setUnpairedAccess(enabled: Boolean): Boolean =
        UserSettings.setUnpairedAccessEnabled(enabled)

    override fun setRecordModePskId(pskId: String): Boolean =
        UserSettings.setRecordModePskId(pskId)

    /**
     * The shared-PSK record backing record mode, minted on first read.
     *
     * `management.md#record-mode` requires the reference to name a real
     * shared-PSK record and `get-pairing-config` treats `record_mode` as
     * non-optional, so one exists. It is device-specific and generated here
     * ("MUST NOT be a fixed default shared across devices"), and it is never
     * distributed: nothing offers it to a server, so nothing can authenticate
     * under it. See the KDoc on [PairingConfig] for why it exists at all.
     */
    private fun recordModePskId(): String {
        UserSettings.getRecordModePskId()?.let { return it }

        val store = UserSettings.getOrCreateTrustStore()
        val psk = secureRandomBytes(Psk.PSK_SIZE)
        // serverId = null makes it a shared-PSK record, which is exactly what
        // record mode must point at.
        val result = store.addRecord(psk, serverId = null)
        if (result !is TrustStore.AddRecordResult.Ok) {
            Log.e(TAG, "Could not provision the record-mode record: $result")
            return ""
        }
        if (!UserSettings.setRecordModePskId(result.record.pskId)) {
            Log.e(TAG, "Could not persist the record-mode reference")
        }
        Log.i(TAG, "Provisioned the record-mode shared record (psk_id=${result.record.pskId})")
        return result.record.pskId
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
                return PairingConfigStore.RotateResult.StorageFailed
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
