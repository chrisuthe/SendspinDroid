import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Hand-rolled Noise_KKpsk2_25519_ChaChaPoly_SHA256 RESPONDER on BouncyCastle.
 *
 * Prototype for audit item 0.1 (issue #189), to be ported to Kotlin as
 * NoiseSession.kt in item 1.2 (issue #193). noise-java cannot do this: it has
 * no psk token and refuses a PSK once the handshake has started.
 *
 * In Sendspin the SERVER is the Noise initiator and the CLIENT is the
 * responder, regardless of who opened the WebSocket. This class is the client
 * side.
 *
 *   KKpsk2:
 *     -> s
 *     <- s
 *     ...
 *     -> e, es, ss
 *     <- e, ee, se, psk
 *
 * The PSK is mixed at the END of message 2 (the psk2 modifier), which is what
 * lets the client read psk_id from message 1's payload and only then choose
 * which PSK to mix -- the reason a library that demands the PSK up front is
 * unusable here.
 */
public final class KKpsk2Responder {

    private static final int HASHLEN = 32;
    private static final int DHLEN = 32;
    private static final int TAGLEN = 16;

    // -- primitives --------------------------------------------------------

    private static byte[] sha256(byte[]... parts) {
        SHA256Digest d = new SHA256Digest();
        for (byte[] p : parts) d.update(p, 0, p.length);
        byte[] out = new byte[HASHLEN];
        d.doFinal(out, 0);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        HMac mac = new HMac(new SHA256Digest());
        mac.init(new KeyParameter(key));
        mac.update(data, 0, data.length);
        byte[] out = new byte[HASHLEN];
        mac.doFinal(out, 0);
        return out;
    }

    /**
     * Noise HKDF. NOT RFC 5869 applied naively: each output is an HMAC over the
     * previous output concatenated with a counter byte. Using a generic HKDF
     * helper here produces a handshake that only fails against a real peer.
     */
    private static byte[][] hkdf(byte[] chainingKey, byte[] ikm, int numOutputs) {
        byte[] tempKey = hmac(chainingKey, ikm);
        byte[] o1 = hmac(tempKey, new byte[] { 0x01 });
        byte[] o2 = hmac(tempKey, concat(o1, new byte[] { 0x02 }));
        if (numOutputs == 2) return new byte[][] { o1, o2 };
        byte[] o3 = hmac(tempKey, concat(o2, new byte[] { 0x03 }));
        return new byte[][] { o1, o2, o3 };
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] dh(byte[] privateKey, byte[] publicKey) {
        byte[] out = new byte[DHLEN];
        X25519.scalarMult(privateKey, 0, publicKey, 0, out, 0);
        return out;
    }

    /** ChaChaPoly nonce: 4 zero bytes then the counter, 64-bit little-endian. */
    private static byte[] nonceBytes(long n) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (n >>> (8 * i));
        return nonce;
    }

    private static byte[] aeadEncrypt(byte[] key, long n, byte[] ad, byte[] plaintext) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(true, new AEADParameters(new KeyParameter(key), TAGLEN * 8, nonceBytes(n), ad));
        byte[] out = new byte[c.getOutputSize(plaintext.length)];
        int len = c.processBytes(plaintext, 0, plaintext.length, out, 0);
        len += c.doFinal(out, len);
        return Arrays.copyOf(out, len);
    }

    private static byte[] aeadDecrypt(byte[] key, long n, byte[] ad, byte[] ciphertext) throws Exception {
        ChaCha20Poly1305 c = new ChaCha20Poly1305();
        c.init(false, new AEADParameters(new KeyParameter(key), TAGLEN * 8, nonceBytes(n), ad));
        byte[] out = new byte[c.getOutputSize(ciphertext.length)];
        int len = c.processBytes(ciphertext, 0, ciphertext.length, out, 0);
        len += c.doFinal(out, len);
        return Arrays.copyOf(out, len);
    }

    // -- symmetric state ---------------------------------------------------

    private byte[] h;
    private byte[] ck;
    private byte[] k;          // null means "no key yet"
    private long n;

    private void initializeSymmetric(String protocolName) {
        byte[] name = protocolName.getBytes(StandardCharsets.UTF_8);
        h = (name.length <= HASHLEN) ? Arrays.copyOf(name, HASHLEN) : sha256(name);
        ck = h.clone();
        k = null;
        n = 0;
    }

    private void mixHash(byte[] data) {
        h = sha256(h, data);
    }

    private void mixKey(byte[] ikm) {
        byte[][] out = hkdf(ck, ikm, 2);
        ck = out[0];
        k = out[1];
        n = 0;
    }

    private void mixKeyAndHash(byte[] ikm) {
        byte[][] out = hkdf(ck, ikm, 3);
        ck = out[0];
        mixHash(out[1]);
        k = out[2];
        n = 0;
    }

    private byte[] encryptAndHash(byte[] plaintext) throws Exception {
        byte[] ct = (k == null) ? plaintext : aeadEncrypt(k, n++, h, plaintext);
        mixHash(ct);
        return ct;
    }

    private byte[] decryptAndHash(byte[] ciphertext) throws Exception {
        byte[] pt = (k == null) ? ciphertext : aeadDecrypt(k, n++, h, ciphertext);
        mixHash(ciphertext);
        return pt;
    }

    // -- handshake ---------------------------------------------------------

    private final byte[] s;      // our static private
    private final byte[] sPub;   // our static public
    private final byte[] rs;     // remote (server) static public
    private byte[] e, ePub, re;

    public byte[] handshakeHash;
    public byte[] recvKey, sendKey;

    public KKpsk2Responder(byte[] staticPrivate, byte[] remoteStaticPublic) {
        this.s = staticPrivate.clone();
        this.sPub = new byte[DHLEN];
        X25519.scalarMultBase(this.s, 0, this.sPub, 0);
        this.rs = remoteStaticPublic.clone();
    }

    public void start(byte[] prologue) {
        initializeSymmetric("Noise_KKpsk2_25519_ChaChaPoly_SHA256");
        mixHash(prologue);
        // Pre-messages, initiator's public keys first (Noise spec section 7).
        mixHash(rs);    // "-> s"  (the server, who is the initiator)
        mixHash(sPub);  // "<- s"  (us, the responder)
    }

    /** Message 1: -> e, es, ss. Returns the decrypted payload (carries psk_id). */
    public byte[] readMessage1(byte[] message) throws Exception {
        re = Arrays.copyOfRange(message, 0, DHLEN);
        mixHash(re);
        // PSK-modified handshakes only: every "e" token calls MixKey on the
        // ephemeral public key in addition to MixHash (Noise spec, psk
        // modifier). Omitting this diverges the state at the very first token
        // and surfaces only as an AEAD tag failure.
        mixKey(re);
        mixKey(dh(s, re));          // es: responder side is DH(s, re)
        mixKey(dh(s, rs));          // ss
        return decryptAndHash(Arrays.copyOfRange(message, DHLEN, message.length));
    }

    /** Message 2: <- e, ee, se, psk. The PSK is mixed LAST, after key selection. */
    public byte[] writeMessage2(byte[] payload, byte[] psk) throws Exception {
        return writeMessage2(payload, psk, null);
    }

    /** @param fixedEphemeral test-only: pin the ephemeral so vectors are reproducible. */
    public byte[] writeMessage2(byte[] payload, byte[] psk, byte[] fixedEphemeral) throws Exception {
        e = new byte[DHLEN];
        if (fixedEphemeral != null) {
            System.arraycopy(fixedEphemeral, 0, e, 0, DHLEN);
        } else {
            new SecureRandom().nextBytes(e);
        }
        ePub = new byte[DHLEN];
        X25519.scalarMultBase(e, 0, ePub, 0);

        mixHash(ePub);
        mixKey(ePub);               // psk modifier: "e" also mixes into the key
        mixKey(dh(e, re));          // ee
        mixKey(dh(e, rs));          // se: responder side is DH(e, rs)
        mixKeyAndHash(psk);         // psk2 -- the whole point
        byte[] ct = encryptAndHash(payload);

        handshakeHash = h.clone();
        byte[][] keys = hkdf(ck, new byte[0], 2);
        // Responder: first output decrypts initiator->responder traffic.
        recvKey = keys[0];
        sendKey = keys[1];
        return concat(ePub, ct);
    }

    // -- interop driver ----------------------------------------------------

    private static byte[] readFrame(DataInputStream in) throws Exception {
        int len = in.readUnsignedShort();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    private static void writeFrame(DataOutputStream out, byte[] data) throws Exception {
        out.writeShort(data.length);
        out.write(data);
        out.flush();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Debug: dump symmetric state after init and after reading a recorded message 1. */
    private static void trace(String[] args) throws Exception {
        byte[] ourStatic = hex(args[1]);
        byte[] serverStatic = hex(args[2]);
        byte[] prologue = args[3].getBytes(StandardCharsets.UTF_8);
        byte[] msg1 = hex(args[4]);

        KKpsk2Responder r = new KKpsk2Responder(ourStatic, serverStatic);
        r.start(prologue);
        System.out.println("after_init_h  " + toHex(r.h));
        System.out.println("after_init_ck " + toHex(r.ck));
        System.out.println("our_pub       " + toHex(r.sPub));
        System.out.println("their_pub     " + toHex(r.rs));
        try {
            byte[] pt = r.readMessage1(msg1);
            System.out.println("msg1_payload  " + new String(pt, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("msg1_FAILED   " + e.getMessage());
        }
        System.out.println("after_m1_h    " + toHex(r.h));
        System.out.println("after_m1_ck   " + toHex(r.ck));
    }

    /** Emit reproducible golden vectors as JSON for the Kotlin port's tests. */
    private static void vectors(String[] args) throws Exception {
        byte[] ourStatic = hex(args[1]);
        byte[] serverStatic = hex(args[2]);
        byte[] psk = hex(args[3]);
        byte[] prologue = args[4].getBytes(StandardCharsets.UTF_8);
        byte[] msg1 = hex(args[5]);
        byte[] fixedEph = hex(args[6]);

        KKpsk2Responder r = new KKpsk2Responder(ourStatic, serverStatic);
        r.start(prologue);
        byte[] payload1 = r.readMessage1(msg1);
        String hAfterM1 = toHex(r.h);
        byte[] msg2 = r.writeMessage2("{}".getBytes(StandardCharsets.UTF_8), psk, fixedEph);

        System.out.println("{");
        System.out.println("  \"protocol\": \"Noise_KKpsk2_25519_ChaChaPoly_SHA256\",");
        System.out.println("  \"prologue_utf8\": \"" + args[4] + "\",");
        System.out.println("  \"client_static_private\": \"" + toHex(ourStatic) + "\",");
        System.out.println("  \"client_static_public\": \"" + toHex(r.sPub) + "\",");
        System.out.println("  \"server_static_public\": \"" + toHex(serverStatic) + "\",");
        System.out.println("  \"psk\": \"" + toHex(psk) + "\",");
        System.out.println("  \"client_ephemeral_private\": \"" + toHex(fixedEph) + "\",");
        System.out.println("  \"message_1\": \"" + toHex(msg1) + "\",");
        System.out.println("  \"message_1_payload_utf8\": " + jsonString(new String(payload1, StandardCharsets.UTF_8)) + ",");
        System.out.println("  \"h_after_message_1\": \"" + hAfterM1 + "\",");
        System.out.println("  \"message_2\": \"" + toHex(msg2) + "\",");
        System.out.println("  \"message_2_payload_utf8\": \"{}\",");
        System.out.println("  \"handshake_hash\": \"" + toHex(r.handshakeHash) + "\",");
        System.out.println("  \"transport_key_recv\": \"" + toHex(r.recvKey) + "\",");
        System.out.println("  \"transport_key_send\": \"" + toHex(r.sendKey) + "\"");
        System.out.println("}");
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void main(String[] args) throws Exception {
        if ("trace".equals(args[0])) {
            trace(args);
            return;
        }
        if ("vectors".equals(args[0])) {
            vectors(args);
            return;
        }
        int port = Integer.parseInt(args[0]);
        byte[] ourStatic = hex(args[1]);
        byte[] serverStatic = hex(args[2]);
        byte[] psk = hex(args[3]);
        byte[] prologue = args[4].getBytes(StandardCharsets.UTF_8);

        try (Socket sock = new Socket("127.0.0.1", port)) {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());

            KKpsk2Responder r = new KKpsk2Responder(ourStatic, serverStatic);
            r.start(prologue);

            byte[] msg1 = readFrame(in);
            byte[] payload1 = r.readMessage1(msg1);
            System.out.println("RESPONDER msg1_payload=" + new String(payload1, StandardCharsets.UTF_8));

            // Sendspin sends the literal two bytes {} as message 2's payload.
            byte[] msg2 = r.writeMessage2("{}".getBytes(StandardCharsets.UTF_8), psk);
            writeFrame(out, msg2);

            System.out.println("RESPONDER h=" + toHex(r.handshakeHash));

            // Transport mode: read one message from the initiator.
            byte[] ct = readFrame(in);
            byte[] pt = aeadDecrypt(r.recvKey, 0, new byte[0], ct);
            System.out.println("RESPONDER transport_recv=" + new String(pt, StandardCharsets.UTF_8));

            // And send one back.
            byte[] reply = aeadEncrypt(r.sendKey, 0, new byte[0],
                    "hello from responder".getBytes(StandardCharsets.UTF_8));
            writeFrame(out, reply);
            System.out.println("RESPONDER done");
        }
    }
}
