package dev.flags.core;

import java.util.List;

/**
 * Everything needed to evaluate one flag in one environment. This is also the wire
 * format the server sends to SDKs, so it carries no tenant or database identifiers.
 *
 * @param salt mixed into the rollout hash so that each flag splits its audience
 *     independently; without it the same users would be first into every rollout
 * @param fallthroughPercentage rollout for contexts that match no rule
 */
public record FlagDefinition(
        String key, String salt, boolean enabled, List<Rule> rules, int fallthroughPercentage) {

    public FlagDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A flag needs a key");
        }
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("A flag needs a salt");
        }
        Bucketing.requirePercentage(fallthroughPercentage);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
