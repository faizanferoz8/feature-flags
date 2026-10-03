package dev.flags.core;

/**
 * The outcome of evaluating a flag, with enough detail to answer "why did this user get
 * that?" without re-deriving it.
 *
 * @param ruleId the rule that matched, when the reason is {@link Reason#RULE_MATCH}
 * @param bucket the context's position in this flag's rollout, 0 to 9999, when a
 *     percentage was consulted
 */
public record Evaluation(String flagKey, boolean value, Reason reason, String ruleId, Integer bucket) {

    static Evaluation off(String flagKey) {
        return new Evaluation(flagKey, false, Reason.OFF, null, null);
    }

    public static Evaluation notFound(String flagKey, boolean defaultValue) {
        return new Evaluation(flagKey, defaultValue, Reason.FLAG_NOT_FOUND, null, null);
    }
}
