package dev.flags.core;

import java.util.List;

/**
 * A targeting rule: if every condition matches, the flag is on for
 * {@code rolloutPercentage} percent of the matching contexts. 100 means everyone who
 * matches, 0 means the rule switches the flag off for them.
 */
public record Rule(String id, String description, List<Condition> conditions, int rolloutPercentage) {

    public Rule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A rule needs an id");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("A rule needs at least one condition");
        }
        Bucketing.requirePercentage(rolloutPercentage);
        conditions = List.copyOf(conditions);
        description = description == null ? "" : description;
    }

    boolean matches(EvaluationContext context) {
        for (Condition condition : conditions) {
            if (!condition.matches(context)) {
                return false;
            }
        }
        return true;
    }
}
