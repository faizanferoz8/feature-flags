package dev.flags.core;

/**
 * Decides whether a flag is on for a context. Pure and stateless: the same definition
 * and context always give the same answer, which is what lets the server, the Java SDK
 * and the console's preview agree with each other.
 *
 * <p>Order of precedence: the kill switch, then the first rule whose conditions all
 * match, then the default rollout.
 */
public final class FlagEvaluator {

    private FlagEvaluator() {}

    public static Evaluation evaluate(FlagDefinition flag, EvaluationContext context) {
        if (!flag.enabled()) {
            return Evaluation.off(flag.key());
        }
        for (Rule rule : flag.rules()) {
            if (rule.matches(context)) {
                return decide(flag, context, rule.rolloutPercentage(), Reason.RULE_MATCH, rule.id());
            }
        }
        return decide(flag, context, flag.fallthroughPercentage(), Reason.FALLTHROUGH, null);
    }

    private static Evaluation decide(
            FlagDefinition flag, EvaluationContext context, int percentage, Reason reason, String ruleId) {
        // 0 and 100 are by far the commonest settings and need no hash.
        if (percentage == 0) {
            return new Evaluation(flag.key(), false, reason, ruleId, null);
        }
        if (percentage == 100) {
            return new Evaluation(flag.key(), true, reason, ruleId, null);
        }
        int bucket = Bucketing.bucket(flag.salt(), context.key());
        return new Evaluation(flag.key(), Bucketing.included(bucket, percentage), reason, ruleId, bucket);
    }
}
