package dev.flags.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Assigns each context a fixed position in a flag's rollout.
 *
 * <p>The position is the first eight bytes of {@code SHA-256(salt + ":" + key)}, read as
 * an unsigned big-endian integer, modulo 10,000. A rollout of p percent includes the
 * positions below {@code p * 100}. Two properties follow, and the tests pin both:
 *
 * <ul>
 *   <li><b>Sticky.</b> The position depends only on the salt and the key, so the same
 *       user gets the same answer on every server, in every SDK, after every restart.
 *   <li><b>Monotonic.</b> Raising the percentage only adds positions, so nobody who had
 *       the feature at 10% loses it at 20%.
 * </ul>
 *
 * <p>Any other SDK can reproduce this with a standard SHA-256; the golden values in
 * {@code BucketingTest} are the compatibility contract.
 */
public final class Bucketing {

    /** Positions per flag. 10,000 gives hundredths of a percent of resolution. */
    public static final int BUCKETS = 10_000;

    private Bucketing() {}

    public static int bucket(String salt, String contextKey) {
        byte[] digest = sha256().digest((salt + ":" + contextKey).getBytes(StandardCharsets.UTF_8));
        long head = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        return (int) Long.remainderUnsigned(head, BUCKETS);
    }

    public static boolean included(int bucket, int percentage) {
        return bucket < percentage * (BUCKETS / 100);
    }

    static void requirePercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("A rollout percentage must be between 0 and 100, got " + percentage);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime is required to provide SHA-256", e);
        }
    }
}
