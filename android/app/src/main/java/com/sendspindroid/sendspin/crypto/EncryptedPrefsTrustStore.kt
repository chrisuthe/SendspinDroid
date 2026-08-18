package com.sendspindroid.sendspin.crypto

import android.util.Log
import com.sendspindroid.UserSettings

/**
 * The trust store, persisted in `UserSettings`' sensitive preferences.
 *
 * Android-only because `androidx.security` is an `app/` dependency. All the
 * record and namespace logic is inherited from [InMemoryTrustStore]; this adds
 * exactly two things - load on construction, flush on change.
 *
 * Subclassing rather than wrapping is deliberate: a delegating wrapper would
 * have to redeclare every member of [TrustStore] purely to add a write, and the
 * one that got missed would drop records with no symptom until the next connect.
 *
 * @param pairingPskId the client's own Pairing PSK id once 2.2 (#203) provides
 *   one. It holds a place in the shared `psk_id` namespace even though it is
 *   never a record.
 */
class EncryptedPrefsTrustStore(
    pairingPskId: String? = null,
) : InMemoryTrustStore(
    initial = PskRecordCodec.decode(UserSettings.getPskRecordsBlob()),
    pairingPskId = pairingPskId,
    storageIsEncrypted = UserSettings.isEncrypted,
) {

    init {
        if (!UserSettings.isEncrypted) {
            // Not fatal: refusing to store would brick the app on OEM devices
            // with a broken Keystore. But the PSKs are on disk in the clear, so
            // it must be visible - `storageIsEncrypted` carries it to the UI.
            Log.w(
                TAG,
                "Storing Sendspin PSK records in UNENCRYPTED preferences: the " +
                    "device Keystore is unavailable. Pairing still works and the " +
                    "records persist, but they are not protected at rest."
            )
        }
    }

    override fun onChanged() {
        if (!UserSettings.setPskRecordsBlob(PskRecordCodec.encode(listRecords()))) {
            // Losing this write means the server keeps a credential we have
            // forgotten, so say so rather than failing silently at next connect.
            Log.e(TAG, "Failed to persist Sendspin PSK records: no storage available")
        }
    }

    private companion object {
        const val TAG = "TrustStore"
    }
}
