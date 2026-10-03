package dev.flags.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BucketingTest {

    private static final int USERS = 100_000;

    @Test
    void goldenValuesNeverChange() {
        // The compatibility contract for SDKs in other languages. If these change, every
        // running rollout reshuffles its audience.
        assertThat(Bucketing.bucket("salt", "user-1")).isEqualTo(GOLDEN_1);
        assertThat(Bucketing.bucket("salt", "user-2")).isEqualTo(GOLDEN_2);
        assertThat(Bucketing.bucket("9f1c2e", "ümlaut@example.com")).isEqualTo(GOLDEN_3);
    }

    // Computed independently with Python's hashlib, not by running this code.
    private static final int GOLDEN_1 = 9_251;
    private static final int GOLDEN_2 = 8_359;
    private static final int GOLDEN_3 = 6_549;

    @Test
    void sameInputsAlwaysLandInTheSameBucket() {
        for (int i = 0; i < 1_000; i++) {
            String key = "user-" + i;
            assertThat(Bucketing.bucket("s", key)).isEqualTo(Bucketing.bucket("s", key)).isBetween(0, 9_999);
        }
    }

    @Test
    void bucketsAreSpreadEvenly() {
        int[] deciles = new int[10];
        for (int i = 0; i < USERS; i++) {
            deciles[Bucketing.bucket("checkout-v2", "user-" + i) / 1_000]++;
        }
        for (int count : deciles) {
            // Each decile expects 10,000; three standard deviations is about 285.
            assertThat((double) count).isCloseTo(USERS / 10.0, within(400.0));
        }
    }

    @Test
    void raisingThePercentageNeverRemovesAnyone() {
        for (int i = 0; i < 20_000; i++) {
            int bucket = Bucketing.bucket("s", "user-" + i);
            boolean previous = false;
            for (int percentage = 0; percentage <= 100; percentage++) {
                boolean now = Bucketing.included(bucket, percentage);
                assertThat(previous && !now).as("user-%d dropped out at %d%%", i, percentage).isFalse();
                previous = now;
            }
        }
    }

    @Test
    void zeroIncludesNobodyAndOneHundredIncludesEverybody() {
        IntStream.range(0, Bucketing.BUCKETS).forEach(bucket -> {
            assertThat(Bucketing.included(bucket, 0)).isFalse();
            assertThat(Bucketing.included(bucket, 100)).isTrue();
        });
    }

    @Test
    void differentSaltsSplitTheAudienceIndependently() {
        // If two flags shared buckets, the same 10% of users would be first into every
        // rollout. With independent salts, about 10% of 10% are in both.
        long inBoth = IntStream.range(0, USERS)
                .mapToObj(i -> "user-" + i)
                .filter(key -> Bucketing.included(Bucketing.bucket("flag-a", key), 10)
                        && Bucketing.included(Bucketing.bucket("flag-b", key), 10))
                .count();
        assertThat((double) inBoth / USERS).isCloseTo(0.01, within(0.003));
    }
}
