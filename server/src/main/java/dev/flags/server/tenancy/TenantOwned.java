package dev.flags.server.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;

/**
 * Base for every entity that belongs to a tenant. The tenant is stamped from the context
 * when the row is first written, so no service has to remember to set it, and the
 * database's WITH CHECK policy would reject the row if it were ever wrong.
 */
@MappedSuperclass
public abstract class TenantOwned {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @PrePersist
    void stampTenant() {
        if (tenantId == null) {
            tenantId = TenantContext.require();
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
