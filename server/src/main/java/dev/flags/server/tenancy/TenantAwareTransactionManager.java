package dev.flags.server.tenancy;

import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells Postgres which tenant each transaction belongs to, as its first statement.
 *
 * <p>{@code set_config(..., true)} is the function form of {@code SET LOCAL}: the value
 * lasts until the transaction ends and then disappears. That matters with a connection
 * pool. A session-level setting would still be there when the connection is handed to
 * the next request, which is exactly the leak this design exists to prevent.
 *
 * <p>With no tenant on the thread the setting is written as empty, and the row-level
 * security policies then match nothing.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        String tenant = TenantContext.current().map(Object::toString).orElse("");
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        holder.getEntityManager().unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                statement.setString(1, tenant);
                statement.execute();
            }
        });
    }
}
