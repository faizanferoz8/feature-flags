package dev.flags.core;

import java.util.Map;

/**
 * Who or what a flag is being evaluated for. The key identifies the subject (a user id,
 * an account id, a device id) and decides which side of a percentage rollout it lands
 * on; attributes are what targeting rules test.
 */
public record EvaluationContext(String key, Map<String, String> attributes) {

    public EvaluationContext {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An evaluation context needs a key");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static EvaluationContext of(String key) {
        return new EvaluationContext(key, Map.of());
    }

    public static EvaluationContext of(String key, Map<String, String> attributes) {
        return new EvaluationContext(key, attributes);
    }
}
