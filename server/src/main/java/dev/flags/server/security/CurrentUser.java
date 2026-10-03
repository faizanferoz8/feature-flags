package dev.flags.server.security;

import java.util.UUID;

/** The authenticated console user, as loaded from the database for this request. */
public record CurrentUser(UUID id, UUID tenantId, String email, Role role) {}
