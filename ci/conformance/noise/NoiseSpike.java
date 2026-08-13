import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.Noise;

/**
 * Spike for audit item 0.1 (issue #189).
 *
 * Answers, against org.signal.forks:noise-java:0.1.1:
 *   Q1 can it construct Noise_KKpsk2_25519_<cipher>_SHA256?
 *   Q2 does it expose the handshake hash h?
 *   Q3 can a PSK be supplied AFTER Noise message 1 has been read?
 *   Q4 are both Sendspin cipher suites constructible?
 *
 * Q1 and Q3 together decide audit decision D3. Sendspin needs psk2 (PSK mixed
 * at the END of message 2) and needs to pick the PSK from the psk_id carried in
 * message 1, so a library that cannot do both buys us nothing.
 */
public class NoiseSpike {

    private static void q(String id, String question) {
        System.out.println();
        System.out.println("--- " + id + ": " + question);
    }

    private static void result(String verdict, String detail) {
        System.out.println("    " + verdict + "  " + detail);
    }

    private static void tryProtocol(String name) {
        try {
            HandshakeState hs = new HandshakeState(name, HandshakeState.RESPONDER);
            result("OK    ", name + " constructed (action=" + hs.getAction() + ")");
            hs.destroy();
        } catch (Throwable t) {
            result("FAIL  ", name + " -> " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("noise-java spike (org.signal.forks:noise-java:0.1.1)");
        System.out.println("java " + System.getProperty("java.version"));

        q("Q1", "can KKpsk2 be constructed?");
        tryProtocol("Noise_KKpsk2_25519_ChaChaPoly_SHA256");
        tryProtocol("Noise_KKpsk2_25519_AESGCM_SHA256");
        System.out.println("    (controls below: patterns the library does claim to support)");
        tryProtocol("Noise_KK_25519_ChaChaPoly_SHA256");
        tryProtocol("NoisePSK_KK_25519_ChaChaPoly_SHA256");

        q("Q2", "is the handshake hash h exposed, and when?");
        try {
            HandshakeState ini = new HandshakeState("Noise_KK_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR);
            HandshakeState res = new HandshakeState("Noise_KK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER);
            ini.getLocalKeyPair().generateKeyPair();
            res.getLocalKeyPair().generateKeyPair();
            byte[] iniPub = new byte[32];
            byte[] resPub = new byte[32];
            ini.getLocalKeyPair().getPublicKey(iniPub, 0);
            res.getLocalKeyPair().getPublicKey(resPub, 0);
            ini.getRemotePublicKey().setPublicKey(resPub, 0);
            res.getRemotePublicKey().setPublicKey(iniPub, 0);

            byte[] prologue = "sendspin-spike-prologue".getBytes("UTF-8");
            ini.setPrologue(prologue, 0, prologue.length);
            res.setPrologue(prologue, 0, prologue.length);
            result("OK    ", "setPrologue(byte[],int,int) accepts an arbitrary prologue");

            ini.start();
            res.start();

            byte[] buf = new byte[1024];
            int n = ini.writeMessage(buf, 0, null, 0, 0);
            byte[] scratch = new byte[1024];
            res.readMessage(buf, 0, n, scratch, 0);
            n = res.writeMessage(buf, 0, null, 0, 0);
            ini.readMessage(buf, 0, n, scratch, 0);

            result("INFO  ", "post-exchange action initiator=" + ini.getAction() + " responder=" + res.getAction());
            byte[] hi = ini.getHandshakeHash();
            byte[] hr = res.getHandshakeHash();
            boolean same = java.util.Arrays.equals(hi, hr);
            result(same ? "OK    " : "FAIL  ",
                    "getHandshakeHash() len=" + (hi == null ? "null" : hi.length)
                            + " matches-on-both-sides=" + same);
            CipherState[] pair = { ini.split().getSender(), res.split().getReceiver() };
            result("OK    ", "split() produced cipher states (" + pair.length + ")");
        } catch (Throwable t) {
            result("FAIL  ", t.getClass().getName() + ": " + t.getMessage());
        }

        q("Q3", "can a PSK be supplied AFTER message 1 is read? (psk_id selection needs this)");
        try {
            HandshakeState res = new HandshakeState("NoisePSK_KK_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER);
            byte[] psk = new byte[32];
            res.setPreSharedKey(psk, 0, psk.length);
            result("INFO  ", "setPreSharedKey BEFORE start() accepted");
            res.getLocalKeyPair().generateKeyPair();
            byte[] pub = new byte[32];
            res.getLocalKeyPair().getPublicKey(pub, 0);
            res.getRemotePublicKey().setPublicKey(pub, 0);
            res.start();
            try {
                res.setPreSharedKey(psk, 0, psk.length);
                result("OK    ", "setPreSharedKey AFTER start() accepted - late injection possible");
            } catch (Throwable t) {
                result("FAIL  ", "setPreSharedKey AFTER start() -> "
                        + t.getClass().getName() + ": " + t.getMessage());
            }
        } catch (Throwable t) {
            result("FAIL  ", "setup: " + t.getClass().getName() + ": " + t.getMessage());
        }

        q("Q4", "are both Sendspin cipher suites available?");
        for (String cipher : new String[] { "ChaChaPoly", "AESGCM" }) {
            try {
                CipherState cs = Noise.createCipher(cipher);
                result("OK    ", cipher + " -> " + cs.getClass().getSimpleName());
                cs.destroy();
            } catch (Throwable t) {
                result("FAIL  ", cipher + " -> " + t.getClass().getName() + ": " + t.getMessage());
            }
        }

        System.out.println();
        System.out.println("--- DECISION (audit D3) ---");
        System.out.println("Adopt the library only if Q1 constructs KKpsk2 AND Q3 allows late PSK injection.");
    }
}
