package dev.flags.core;

/** Why an evaluation produced the value it did. */
public enum Reason {
    /** The flag is switched off in this environment; rules were not consulted. */
    OFF,
    /** A targeting rule matched and decided the value. */
    RULE_MATCH,
    /** No rule matched; the default rollout decided the value. */
    FALLTHROUGH,
    /** The flag does not exist in this environment; the caller's default was returned. */
    FLAG_NOT_FOUND
}
