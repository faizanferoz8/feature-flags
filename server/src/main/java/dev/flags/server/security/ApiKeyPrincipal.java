package dev.flags.server.security;

import java.util.UUID;

/** An SDK, identified by its key. A key belongs to exactly one environment of one tenant. */
public record ApiKeyPrincipal(UUID keyId, UUID tenantId, UUID environmentId, String environmentKey) {}
