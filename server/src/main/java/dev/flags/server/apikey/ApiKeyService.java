package dev.flags.server.apikey;

import dev.flags.server.audit.AuditAction;
import dev.flags.server.audit.AuditService;
import dev.flags.server.flag.Environment;
import dev.flags.server.flag.EnvironmentChanges;
import dev.flags.server.flag.EnvironmentRepository;
import dev.flags.server.security.ApiKeys;
import dev.flags.server.security.CurrentUser;
import dev.flags.server.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    public record CreateKeyRequest(@NotBlank @Size(max = 100) String name, @NotBlank String environment) {}

    public record KeyView(
            UUID id,
            String name,
            String environment,
            String prefix,
            String createdBy,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt) {

        static KeyView of(ApiKey key) {
            return new KeyView(
                    key.getId(),
                    key.getName(),
                    key.getEnvironment().getKey(),
                    key.getPrefix(),
                    key.getCreatedBy(),
                    key.getCreatedAt(),
                    key.getLastUsedAt(),
                    key.getRevokedAt());
        }
    }

    /** The only time the key itself leaves the server. */
    public record CreatedKey(KeyView key, String secret) {}

    private final ApiKeyRepository keys;
    private final EnvironmentRepository environments;
    private final EnvironmentChanges changes;
    private final AuditService audit;

    ApiKeyService(
            ApiKeyRepository keys, EnvironmentRepository environments, EnvironmentChanges changes, AuditService audit) {
        this.keys = keys;
        this.environments = environments;
        this.changes = changes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<KeyView> list() {
        return keys.findAllWithEnvironment().stream().map(KeyView::of).toList();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CreatedKey create(CreateKeyRequest request, CurrentUser creator) {
        Environment environment = environments
                .findByKey(request.environment())
                .orElseThrow(() -> ApiException.badRequest("There is no environment '" + request.environment() + "'."));
        String secret = ApiKeys.generate();
        ApiKey key = keys.save(new ApiKey(
                environment, request.name().trim(), ApiKeys.visiblePrefix(secret), ApiKeys.hash(secret), creator.email()));
        audit.record(AuditAction.API_KEY_CREATED, key.getName(), environment.getKey(), null, Map.of("prefix", key.getPrefix()));
        return new CreatedKey(KeyView.of(key), secret);
    }

    /** Stops the key authenticating, and closes any stream it has open on any instance. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void revoke(UUID id) {
        ApiKey key = keys.findById(id).orElseThrow(() -> ApiException.notFound("No such API key."));
        if (key.getRevokedAt() != null) {
            return;
        }
        key.revoke();
        audit.record(
                AuditAction.API_KEY_REVOKED,
                key.getName(),
                key.getEnvironment().getKey(),
                Map.of("prefix", key.getPrefix()),
                null);
        changes.keyRevoked(key.getId());
    }
}
