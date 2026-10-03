package dev.flags.server.apikey;

import dev.flags.server.flag.Environment;
import dev.flags.server.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String prefix;

    @Column(name = "key_hash", nullable = false, updatable = false)
    private String keyHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Written by the database when the key authenticates, never by this entity. */
    @Column(name = "last_used_at", insertable = false, updatable = false)
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {}

    ApiKey(Environment environment, String name, String prefix, String keyHash, String createdBy) {
        this.environment = environment;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.createdBy = createdBy;
    }

    void revoke() {
        revokedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
