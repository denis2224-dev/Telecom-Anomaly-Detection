package md.utm.telecom.notifications.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

public final class PushSubscriptionCipher {
    private final Map<Short, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json;

    public PushSubscriptionCipher(Map<Short, byte[]> rawKeys, ObjectMapper json) {
        this.keys = rawKeys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> {
                    if (entry.getValue().length != 32) throw new IllegalArgumentException("AES-256 key required");
                    return new SecretKeySpec(entry.getValue().clone(), "AES");
                }));
        this.json = json;
    }

    public Sealed seal(short keyVersion, String endpoint, String p256dh, String auth) {
        if (endpoint == null || endpoint.length() > 2048 || p256dh == null || p256dh.length() > 256
                || auth == null || auth.length() > 256) throw new IllegalArgumentException("Invalid subscription length");
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            byte[] plain = json.writeValueAsBytes(Map.of("endpoint", endpoint, "p256dh", p256dh, "auth", auth));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(keyVersion), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8));
            return new Sealed(HexFormat.of().formatHex(hash), ciphertext, nonce, keyVersion);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot encrypt subscription", failure);
        }
    }

    public byte[] open(Sealed sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(sealed.keyVersion()), new GCMParameterSpec(128, sealed.nonce()));
            return cipher.doFinal(sealed.ciphertext());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot decrypt subscription", failure);
        }
    }

    private SecretKey key(short version) {
        SecretKey key = keys.get(version);
        if (key == null) throw new IllegalArgumentException("Unknown subscription key version");
        return key;
    }

    public record Sealed(String endpointHash, byte[] ciphertext, byte[] nonce, short keyVersion) {
        public Sealed {
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
