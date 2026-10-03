package dev.flags.core;

/** How a condition compares a context attribute with its configured values. */
public enum Operator {
    IN,
    NOT_IN,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN
}
