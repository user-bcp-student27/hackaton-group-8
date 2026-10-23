package pe.wallet;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

final class PinSecurity {
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Credential DUMMY = create("000000");

    record Credential(String salt, String hash) {}

    private PinSecurity() {}

    static Credential create(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new Credential(HexFormat.of().formatHex(salt), derive(pin, salt));
    }

    static boolean matches(String pin, String salt, String expectedHash) {
        return MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash),
                HexFormat.of().parseHex(derive(pin, HexFormat.of().parseHex(salt))));
    }

    static void dummyCheck(String pin) {
        matches(pin, DUMMY.salt(), DUMMY.hash());
    }

    private static String derive(String pin, byte[] salt) {
        char[] password = pin.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        Arrays.fill(password, '\0');
        try {
            return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded());
        } catch (Exception error) {
            throw new IllegalStateException("PIN hashing is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }
}