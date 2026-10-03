package dev.flags.server.flag;

import dev.flags.server.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks an environment as changed, inside the transaction that changes it.
 *
 * <p>Two things happen. The environment's revision goes up by one, and a notification
 * is queued on the {@value #CHANNEL} channel. Postgres delivers a notification only
 * when its transaction commits, and never if it rolls back, so listeners hear about
 * exactly the changes that happened, after they are visible.
 *
 * <p>The UPDATE also takes the environment's row lock until commit. Concurrent changes
 * to one environment therefore queue up, and revision order is commit order.
 */
@Component
public class EnvironmentChanges {

    public static final String CHANNEL = "flag_changes";

    private final EntityManager entityManager;

    EnvironmentChanges(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(Environment environment) {
        UUID tenantId = TenantContext.require();
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement bump = connection.prepareStatement(
                    "update environments set revision = revision + 1 where id = ? returning revision")) {
                bump.setObject(1, environment.getId());
                try (ResultSet row = bump.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalStateException("Environment " + environment.getKey() + " is not visible");
                    }
                }
            }
            notify(connection, ChangeMessage.snapshot(tenantId, environment.getId()));
        });
    }

    /** Always in the same order, so two transactions touching every environment cannot deadlock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(Collection<Environment> environments) {
        environments.stream().sorted(Comparator.comparing(Environment::getId)).forEach(this::changed);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void keyRevoked(UUID keyId) {
        entityManager.unwrap(Session.class).doWork(connection -> notify(connection, ChangeMessage.revoked(keyId)));
    }

    private static void notify(java.sql.Connection connection, ChangeMessage message) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select pg_notify(?, ?)")) {
            statement.setString(1, CHANNEL);
            statement.setString(2, message.encode());
            statement.execute();
        }
    }
}
