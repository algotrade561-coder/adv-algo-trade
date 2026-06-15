package com.algo.trade.auth.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption converter for sensitive String columns.
 *
 * Master key is resolved (in order) from:
 *   1. JVM property:  -DMULTIUSER_SECRET_KEY=...
 *   2. Env variable:  MULTIUSER_SECRET_KEY
 *   3. Fallback dev key (NOT for production) — logs a warning.
 *
 * Output format: "v1:" + base64(IV(12) || CIPHERTEXT || TAG(16))
 *
 * If a value is read that is NOT prefixed with "v1:" it is assumed plaintext
 * (legacy) and returned as-is — this lets existing rows keep working until
 * they're re-saved (at which point they'll be encrypted).
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);
    private static final String PREFIX = "v1:";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecretKey KEY = loadKey();
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance(AES_GCM);
            c.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt column value", ex);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext row — return as-is. Will be encrypted on next save.
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(AES_GCM);
            c.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt column value — wrong master key?", ex);
        }
    }

    private static SecretKey loadKey() {
        String raw = System.getProperty("MULTIUSER_SECRET_KEY");
        if (raw == null || raw.isBlank()) raw = System.getenv("MULTIUSER_SECRET_KEY");
        if (raw == null || raw.isBlank()) {
            // Check if multi-user mode is enabled — if so, refuse to start with dev key
            String multiUserEnabled = System.getenv("TRADING_MULTIUSER_ENABLED");
            if (multiUserEnabled == null) multiUserEnabled = System.getProperty("trading.multiuser.enabled", "false");
            if ("true".equalsIgnoreCase(multiUserEnabled)) {
                throw new IllegalStateException(
                    "[SECURITY] MULTIUSER_SECRET_KEY is NOT set but multi-user mode is enabled. " +
                    "Set MULTIUSER_SECRET_KEY environment variable to a secure random string (32+ chars) " +
                    "before starting the application. All user secrets (API keys, access tokens) are " +
                    "encrypted with this key.");
            }
            log.warn("[Crypto] MULTIUSER_SECRET_KEY not set — using INSECURE dev key. " +
                     "Set MULTIUSER_SECRET_KEY env var for production.");
            raw = "dev-only-insecure-multiuser-key-please-override";
        }
        try {
            byte[] key32 = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key32, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key", e);
        }
    }
}
