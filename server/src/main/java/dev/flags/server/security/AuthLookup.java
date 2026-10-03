package dev.flags.server.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two questions that have to be answered before a tenant is known. Row-level
 * security would hide every row from them, so they go through SECURITY DEFINER
 * functions that return only what authentication needs.
 */
@Repository
public class AuthLookup {

    public record UserCredentials(UUID id, UUID tenantId, String email, String passwordHash, Role role) {}

    private final JdbcTemplate jdbc;

    AuthLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserCredentials> findUser(String email) {
        return jdbc
                .query(
                        "select id, tenant_id, email, password_hash, role from auth_find_user(?)",
                        (row, n) -> new UserCredentials(
                                row.getObject("id", UUID.class),
                                row.getObject("tenant_id", UUID.class),
                                row.getString("email"),
                                row.getString("password_hash"),
                                Role.valueOf(row.getString("role"))),
                        email)
                .stream()
                .findFirst();
    }

    public Optional<ApiKeyPrincipal> findApiKey(String keyHash) {
        return jdbc
                .query(
                        "select id, tenant_id, environment_id, environment_key from auth_find_api_key(?)",
                        (row, n) -> new ApiKeyPrincipal(
                                row.getObject("id", UUID.class),
                                row.getObject("tenant_id", UUID.class),
                                row.getObject("environment_id", UUID.class),
                                row.getString("environment_key")),
                        keyHash)
                .stream()
                .findFirst();
    }
}
