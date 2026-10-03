package dev.flags.server.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant the current thread is working for. It is set once per request by whichever
 * filter authenticated the caller, and read in exactly one place:
 * {@link TenantAwareTransactionManager}, which hands it to Postgres.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant is set for this thread");
        }
        return tenantId;
    }

    /** Sets the tenant until the returned scope is closed, then restores what was there before. */
    public static Scope open(UUID tenantId) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        try (Scope ignored = open(tenantId)) {
            return work.get();
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
