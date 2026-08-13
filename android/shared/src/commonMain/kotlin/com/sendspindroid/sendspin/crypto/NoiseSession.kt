package com.sendspindroid.sendspin.crypto

/**
 * The Noise `KKpsk2` handshake, responder side.
 *
 *     KKpsk2:
 *       -> s
 *       <- s
 *       ...
 *       -> e, es, ss
 *       <- e, ee, se, psk
 *
 * In Sendspin the SERVER is the Noise initiator and the CLIENT is the
 * responder, regardless of which side opened the WebSocket
 * (`connection.md#pattern`). This is the client side.
 *
 * The `psk2` modifier mixes the PSK at the END of message 2. That is what makes
 * the whole design work: message 1's payload is decryptable without any PSK, so
 * the client can read `psk_id` from it and only then choose which PSK to mix.
 *
 * ## Shape
 *
 * The handshake is strictly linear, and each step is offered by a different
 * type so the compiler enforces the order and the transport keys never exist as
 * loose values a caller could swap:
 *
 * ```
 * val handshake = NoiseHandshake.responder(ourStatic, theirStatic, suite, prologue)
 * val pskId = handshake.readMessage1(noiseBytes)     // pick a PSK from this
 * val (message2, transport) = handshake.writeMessage2(psk)
 * transport.encrypt(...) / transport.decrypt(...)
 * ```
 *
 * Every failure here closes the WebSocket with no application-level error
 * message (`connection.md#failure-handling`), so [NoiseHandshakeException.reason]
 * is the only diagnostic that will ever exist for a field failure.
 */
class NoiseHandshake internal constructor(
    private val suite: NoiseCipherSuite,
    private val staticPrivateKey: ByteArray,
    private val remoteStaticPublicKey: ByteArray,
    prologue: ByteArray,
    /**
     * Test seam. Not on the public factory on purpose: an optional
     * "use this exact ephemeral key" argument on a production API is a
     * catastrophic footgun that looks like an ordinary parameter.
     */
    private val generateEphemeral: () -> ByteArray,
) {
    private val symmetric = NoiseSymmetricState(suite)
    private val staticPublicKey = x25519PublicKey(staticPrivateKey)
    private var remoteEphemeral: ByteArray? = null
    private var phase = Phase.AwaitingMessage1

    private enum class Phase { AwaitingMessage1, AwaitingMessage2, Done, Failed }

    init {
        symmetric.mixHash(prologue)
        // Pre-messages, in pattern order: `-> s` is the initiator's static (the
        // server), `<- s` is ours. Reversing these produces a handshake that
        // looks fine locally and fails at the peer's message-2 AEAD.
        symmetric.mixHash(remoteStaticPublicKey)
        symmetric.mixHash(staticPublicKey)
    }

    companion object {
        /**
         * @param prologue the exact wire bytes of `client/init` followed by the
         *   exact wire bytes of `server/init`. This parameter is a [ByteArray]
         *   and not a parsed message on purpose: the spec requires hashing the
         *   bytes "exactly as sent and received, not a re-encoding of the parsed
         *   message", and `kotlinx.serialization` will not round-trip
         *   byte-identically. For a re-handshake this is instead the prior
         *   handshake's hash (`connection.md#re-handshake`).
         */
        fun responder(
            staticPrivateKey: ByteArray,
            remoteStaticPublicKey: ByteArray,
            suite: NoiseCipherSuite,
            prologue: ByteArray,
        ): NoiseHandshake = NoiseHandshake(
            suite = suite,
            staticPrivateKey = staticPrivateKey,
            remoteStaticPublicKey = remoteStaticPublicKey,
            prologue = prologue,
            generateEphemeral = { secureRandomBytes(DH_LEN) },
        )
    }

    /**
     * Process Noise message 1 (`-> e, es, ss`) and return its decrypted payload.
     *
     * The payload carries `psk_id`; parsing it is the caller's job, because PSK
     * selection is protocol policy rather than crypto. No PSK has been mixed in
     * at this point, which is precisely why this works.
     */
    fun readMessage1(message: ByteArray): ByteArray {
        check(Phase.AwaitingMessage1)
        // Validate the length up front. copyOfRange zero-pads rather than
        // throwing when the range runs past the end, so a truncated frame would
        // otherwise be processed as an all-zero ephemeral key and surface much
        // later as an AEAD failure indistinguishable from a key mismatch.
        if (message.size < DH_LEN + AEAD_TAG_LEN) {
            fail(
                NoiseHandshakeException.Cause.MalformedMessage,
                "handshake message 1 truncated: ${message.size} bytes, " +
                    "need at least ${DH_LEN + AEAD_TAG_LEN}",
            )
        }
        if (message.size > MAX_NOISE_MESSAGE) {
            fail(
                NoiseHandshakeException.Cause.MessageTooLarge,
                "handshake message 1 is ${message.size} bytes",
            )
        }
        return runFailing {
            val re = message.copyOfRange(0, DH_LEN)
            remoteEphemeral = re
            symmetric.mixHash(re)
            // Under any `psk` modifier the `e` token calls MixKey on the
            // ephemeral public key IN ADDITION to MixHash. Omitting it diverges
            // the state at the very first token and shows up only as an AEAD
            // failure several steps later.
            symmetric.mixKey(re)
            // Token naming: the first letter is the initiator's key, the second
            // the responder's. As responder: es = DH(ourStatic, theirEphemeral),
            // ss = DH(ourStatic, theirStatic).
            symmetric.mixKey(x25519(staticPrivateKey, re))
            symmetric.mixKey(x25519(staticPrivateKey, remoteStaticPublicKey))
            val payload = symmetric.decryptAndHash(message.copyOfRange(DH_LEN, message.size))
            phase = Phase.AwaitingMessage2
            payload
        }
    }

    /**
     * Produce Noise message 2 (`<- e, ee, se, psk`) and enter transport mode.
     *
     * @param psk the 32-byte PSK selected from message 1's `psk_id`.
     * @return the Noise bytes to send, and the transport this session becomes.
     */
    fun writeMessage2(psk: ByteArray): Message2 {
        check(Phase.AwaitingMessage2)
        require(psk.size == 32) { "Sendspin PSKs are 32 bytes" }
        val re = remoteEphemeral ?: fail(
            NoiseHandshakeException.Cause.WrongPhase,
            "no remote ephemeral; message 1 was not processed",
        )
        return runFailing {
            val ephemeralPrivate = generateEphemeral()
            require(ephemeralPrivate.size == DH_LEN) { "ephemeral key must be 32 bytes" }
            val ephemeralPublic = x25519PublicKey(ephemeralPrivate)

            symmetric.mixHash(ephemeralPublic)
            symmetric.mixKey(ephemeralPublic)                       // psk modifier, again
            symmetric.mixKey(x25519(ephemeralPrivate, re))          // ee
            symmetric.mixKey(x25519(ephemeralPrivate, remoteStaticPublicKey))  // se
            symmetric.mixKeyAndHash(psk)                            // psk2: mixed LAST

            // The literal two bytes {} - the spec is explicit that this is not a
            // zero-length payload.
            val ciphertext = symmetric.encryptAndHash(MESSAGE_2_PAYLOAD)
            val handshakeHash = symmetric.handshakeHash.copyOf()
            val (receive, send) = symmetric.split()
            ephemeralPrivate.fill(0)
            phase = Phase.Done
            Message2(ephemeralPublic + ciphertext, NoiseTransport(receive, send, handshakeHash))
        }
    }

    /** The output of [writeMessage2]. */
    class Message2 internal constructor(
        /** Base64url-encode these into `noise/handshake.data`. */
        val message: ByteArray,
        val transport: NoiseTransport,
    ) {
        operator fun component1() = message
        operator fun component2() = transport
    }

    private fun check(expected: Phase) {
        if (phase != expected) {
            throw NoiseHandshakeException(
                NoiseHandshakeException.Cause.WrongPhase,
                "handshake is in phase $phase, expected $expected",
            )
        }
    }

    /**
     * Any throw leaves the symmetric state half-mutated, so the handshake is
     * marked terminally failed rather than left callable. Noise mandates
     * aborting on failure; making that structural means a caller cannot retry
     * into a corrupted transcript.
     */
    private inline fun <T> runFailing(body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        phase = Phase.Failed
        symmetric.destroy()
        throw e
    }

    private fun fail(cause: NoiseHandshakeException.Cause, message: String): Nothing {
        phase = Phase.Failed
        throw NoiseHandshakeException(cause, message)
    }
}

internal val MESSAGE_2_PAYLOAD = "{}".encodeToByteArray()

/**
 * Transport mode: the post-handshake encrypted channel.
 *
 * Owns both cipher states and their nonce counters. The keys are deliberately
 * not reachable from outside - two `ByteArray`s of identical type whose order
 * carries essential meaning are trivially swapped, and a swap produces a
 * handshake that completes cleanly and then fails on the first frame.
 */
class NoiseTransport internal constructor(
    private val receiveState: NoiseCipherState,
    private val sendState: NoiseCipherState,
    handshakeHash: ByteArray,
) {
    /**
     * The final handshake hash `h`.
     *
     * Channel-binding data, not a secret. Needed as the prologue for an in-band
     * re-handshake (`connection.md#re-handshake`) and later as part of the CPace
     * `sid`. Returns a copy so a caller cannot mutate our state.
     */
    val handshakeHash: ByteArray = handshakeHash
        get() = field.copyOf()

    /** Transport messages use empty associated data. */
    fun encrypt(plaintext: ByteArray): ByteArray =
        sendState.encryptWithAd(EMPTY, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray =
        receiveState.decryptWithAd(EMPTY, ciphertext)

    /** Best-effort wipe of both transport keys. */
    fun destroy() {
        receiveState.destroy()
        sendState.destroy()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
