package com.sendspindroid.sendspin.crypto

/**
 * The client's Sendspin identity: a long-lived Curve25519 keypair whose public
 * half IS the `client_id`.
 *
 * `README.md#definitions`: "A Curve25519 keypair used to identify a client or
 * server in the Noise handshake. The base64url-encoded public key (43
 * characters, no padding) serves as the `client_id` or `server_id`. Persistent
 * across reboots."
 *
 * That persistence is not cosmetic. The key is a pre-message input to every
 * KKpsk2 handshake, and it is half of the pairing token. Regenerating it makes
 * the client a different device to every server it has ever paired with, and
 * the resulting failure is a silent socket close.
 *
 * The private key never leaves this object; [toString] renders only the public
 * identifier so a stray log line cannot leak it.
 */
class ClientIdentity(privateKey: ByteArray) {

    init {
        require(privateKey.size == KEY_SIZE) {
            "a Curve25519 private key is $KEY_SIZE bytes, got ${privateKey.size}"
        }
    }

    private val secret = privateKey.copyOf()

    /** The raw 32-byte public key, as the Noise handshake needs it. */
    val publicKey: ByteArray = x25519PublicKey(secret)

    /** The 43-character base64url `client_id` that goes on the wire. */
    val clientId: String = Base64Url.encode(publicKey)

    /** A copy. The caller must not retain it longer than the handshake. */
    internal fun privateKeyBytes(): ByteArray = secret.copyOf()

    override fun toString(): String = "ClientIdentity($clientId)"

    companion object {
        const val KEY_SIZE = 32

        /** Mint a new identity from the platform CSPRNG. */
        fun generate(): ClientIdentity = ClientIdentity(secureRandomBytes(KEY_SIZE))

        /**
         * Rebuild from a stored private key.
         *
         * @return null if [base64Url] is not a 32-byte base64url value. The
         *   caller must treat that as "no identity stored" rather than silently
         *   minting a new one over the top - see the storage layer.
         */
        fun fromStoredKey(base64Url: String): ClientIdentity? {
            val bytes = Base64Url.decodeOrNull(base64Url) ?: return null
            return if (bytes.size == KEY_SIZE) ClientIdentity(bytes) else null
        }

        /** Encode a private key for storage. */
        fun encodeForStorage(identity: ClientIdentity): String =
            Base64Url.encode(identity.secret)
    }
}
