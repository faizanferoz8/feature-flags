package dev.flags.server.flag;

import dev.flags.server.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flags")
public class Flag extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    /** Fixed for the life of the flag: changing it would reshuffle every rollout in progress. */
    @Column(nullable = false, updatable = false)
    private String salt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Flag() {}

    Flag(String key, String name, String description) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.salt = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    void describe(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSalt() {
        return salt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
