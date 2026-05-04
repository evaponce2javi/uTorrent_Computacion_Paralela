package utorrent.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Capa de Servicio (F1). Detecta fallas Bizantinas de valor verificando el SHA-1
 * de cada pieza recibida contra el hash esperado del .torrent.
 *
 * <p>Usa {@link MessageDigest#isEqual(byte[], byte[])} (comparación de tiempo constante)
 * para mitigar ataques de timing.
 */
public class IntegrityChecker {

    private final ThreadLocal<MessageDigest> sha1 = ThreadLocal.withInitial(() -> {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    });

    public boolean verifySHA1(byte[] data, byte[] expectedHash) {
        MessageDigest md = sha1.get();
        md.reset();
        byte[] computed = md.digest(data);
        return MessageDigest.isEqual(computed, expectedHash);
    }
}