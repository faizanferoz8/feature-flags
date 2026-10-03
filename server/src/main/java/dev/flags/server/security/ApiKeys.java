package dev.flags.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes SDK keys.
 *
 * <p>Keys are 32 random bytes, so unlike passwords they cannot be guessed and need no
 * slow hash: a plain SHA-256 is enough to make a leaked database useless, and it is
 * deterministic, which lets a key be looked up by its hash in one indexed read.
 */
public final class ApiKeys {

    public static final String PREFIX = "ffk_";
    private static final int VISIBLE_CHARACTERS = PREFIX.length() + 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime is required to provide SHA-256", e);
        }
    }

    /** The part of a key that is safe to show in a list so its owner can tell keys apart. */
    public static String visiblePrefix(String key) {
        return key.substring(0, VISIBLE_CHARACTERS);
    }
}
