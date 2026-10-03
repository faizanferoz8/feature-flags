package dev.flags.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void keysAreUniqueAndRecognisable() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            keys.add(ApiKeys.generate());
        }

        assertThat(keys).hasSize(1_000).allSatisfy(key -> assertThat(key).startsWith("ffk_").hasSize(47));
    }

    @Test
    void hashingIsDeterministicSoAKeyCanBeFoundByItsHash() {
        String key = ApiKeys.generate();

        assertThat(ApiKeys.hash(key)).isEqualTo(ApiKeys.hash(key)).hasSize(64).doesNotContain(key);
        assertThat(ApiKeys.hash(key)).isNotEqualTo(ApiKeys.hash(key + "x"));
    }

    @Test
    void theHashMatchesAStandardSha256() {
        // echo -n "ffk_example" | sha256sum
        assertThat(ApiKeys.hash("ffk_example")).isEqualTo("825db0eaa6f42d23c325ea946f7def27a529fbea5a1aa46af6d6830c68c3cf71");
    }
}
