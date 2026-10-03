package dev.flags.server.sdk;

import dev.flags.core.FlagDefinition;
import dev.flags.core.Snapshot;
import dev.flags.server.flag.Environment;
import dev.flags.server.flag.EnvironmentRepository;
import dev.flags.server.flag.FlagConfig;
import dev.flags.server.flag.FlagConfigRepository;
import dev.flags.server.tenancy.TenantContext;
import dev.flags.server.web.ApiException;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves each environment's flags from memory.
 *
 * <p>SDK traffic is all reads of the same few documents, so each environment's snapshot
 * is built once and kept. What keeps it honest is the revision: an entry is only ever
 * replaced by one with a higher revision, so it does not matter in which order a slow
 * loader and a change notification finish. Whichever read the newer state wins.
 *
 * <p>That argument needs a snapshot's revision and its contents to describe the same
 * moment. They are read by two statements, so the transaction runs at REPEATABLE READ,
 * which gives both statements one view of the database. At the default level a change
 * could commit between them and produce new contents labelled with the old revision,
 * or the reverse.
 */
@Service
public class SnapshotService {

    private final ConcurrentHashMap<UUID, Published> cache = new ConcurrentHashMap<>();
    private final EnvironmentRepository environments;
    private final FlagConfigRepository configs;
    private final EntityManager entityManager;
    private final TransactionTemplate consistentRead;
    private final JsonMapper json;

    SnapshotService(
            EnvironmentRepository environments,
            FlagConfigRepository configs,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            JsonMapper json) {
        this.environments = environments;
        this.configs = configs;
        this.entityManager = entityManager;
        this.json = json;
        this.consistentRead = new TransactionTemplate(transactionManager);
        this.consistentRead.setReadOnly(true);
        this.consistentRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The current snapshot for an environment of the tenant this thread is working for. */
    public Published current(UUID environmentId) {
        Published cached = cache.get(environmentId);
        return cached != null ? cached : keepNewest(load(environmentId));
    }

    /** Re-reads an environment after a change, on a thread that belongs to no request. */
    public Published refresh(UUID tenantId, UUID environmentId) {
        return TenantContext.callAs(tenantId, () -> keepNewest(load(environmentId)));
    }

    /** Re-reads everything held, for when changes may have been missed. */
    public List<Published> refreshAll() {
        return cache.values().stream()
                .map(held -> refresh(held.tenantId(), held.environmentId()))
                .toList();
    }

    public UUID environmentId(String environmentKey) {
        return consistentRead.execute(status -> environments
                .findByKey(environmentKey)
                .map(Environment::getId)
                .orElseThrow(() -> ApiException.notFound("There is no environment '" + environmentKey + "'.")));
    }

    private Published keepNewest(Published loaded) {
        return cache.merge(
                loaded.environmentId(), loaded, (held, fresh) -> fresh.revision() > held.revision() ? fresh : held);
    }

    private Published load(UUID environmentId) {
        UUID tenantId = TenantContext.require();
        Snapshot snapshot = consistentRead.execute(status -> {
            Environment environment = environments
                    .findById(environmentId)
                    .orElseThrow(() -> ApiException.notFound("The environment no longer exists."));
            long revision = ((Number) entityManager
                            .createNativeQuery("select revision from environments where id = :id")
                            .setParameter("id", environmentId)
                            .getSingleResult())
                    .longValue();
            List<FlagDefinition> flags = configs.findByEnvironmentId(environmentId).stream()
                    .map(FlagConfig::toDefinition)
                    .toList();
            return new Snapshot(environment.getKey(), revision, flags);
        });
        return new Published(
                tenantId, environmentId, snapshot, json.writeValueAsString(snapshot), "\"" + snapshot.revision() + "\"");
    }
}
