package dev.flags.server.flag;

import java.util.UUID;

/** What travels on the notification channel. Small on purpose: listeners re-read the database. */
public record ChangeMessage(Kind kind, UUID tenantId, UUID subjectId) {

    public enum Kind {
        /** An environment's flags changed; {@code subjectId} is the environment. */
        SNAPSHOT,
        /** An API key was revoked; {@code subjectId} is the key. */
        KEY_REVOKED
    }

    private static final UUID NONE = new UUID(0, 0);

    static ChangeMessage snapshot(UUID tenantId, UUID environmentId) {
        return new ChangeMessage(Kind.SNAPSHOT, tenantId, environmentId);
    }

    static ChangeMessage revoked(UUID keyId) {
        return new ChangeMessage(Kind.KEY_REVOKED, NONE, keyId);
    }

    public String encode() {
        return kind + "|" + tenantId + "|" + subjectId;
    }

    public static ChangeMessage decode(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Unreadable change message: " + payload);
        }
        return new ChangeMessage(Kind.valueOf(parts[0]), UUID.fromString(parts[1]), UUID.fromString(parts[2]));
    }
}
