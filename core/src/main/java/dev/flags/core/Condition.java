package dev.flags.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One test against one attribute of the context. With several values, any one of them
 * satisfying the operator is enough ({@code NOT_IN} needs all of them to differ).
 *
 * <p>An attribute the context does not carry never matches, whatever the operator. That
 * includes {@code NOT_IN}: "country not in [US]" should not sweep up every anonymous
 * request that has no country at all.
 */
public record Condition(String attribute, Operator operator, List<String> values) {

    /** The attribute name that refers to the context key itself. */
    public static final String KEY_ATTRIBUTE = "key";

    public Condition {
        Objects.requireNonNull(operator, "operator");
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("A condition needs an attribute");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("A condition needs at least one value");
        }
        values = List.copyOf(values);
    }

    boolean matches(EvaluationContext context) {
        String actual = KEY_ATTRIBUTE.equals(attribute) ? context.key() : context.attributes().get(attribute);
        if (actual == null) {
            return false;
        }
        return switch (operator) {
            case IN -> values.contains(actual);
            case NOT_IN -> !values.contains(actual);
            case CONTAINS -> values.stream().anyMatch(actual::contains);
            case STARTS_WITH -> values.stream().anyMatch(actual::startsWith);
            case ENDS_WITH -> values.stream().anyMatch(actual::endsWith);
            case GREATER_THAN -> compareNumerically(actual, 1);
            case LESS_THAN -> compareNumerically(actual, -1);
        };
    }

    private boolean compareNumerically(String actual, int wantedSign) {
        BigDecimal number = parse(actual);
        if (number == null) {
            return false;
        }
        for (String value : values) {
            BigDecimal bound = parse(value);
            if (bound != null && Integer.signum(number.compareTo(bound)) == wantedSign) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
