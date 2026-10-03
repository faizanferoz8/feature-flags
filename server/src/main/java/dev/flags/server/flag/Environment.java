package dev.flags.server.flag;

import dev.flags.server.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;

/**
 * A place a flag can have its own configuration. The environment's revision counter is
 * deliberately not mapped here: it is only ever changed by {@link EnvironmentChanges},
 * in SQL, so that it cannot be overwritten by a stale entity.
 */
@Entity
@Table(name = "environments")
public class Environment extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Environment() {}

    private Environment(String key, String name, int sortOrder) {
        this.key = key;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    /** What every new organization starts with. */
    public static List<Environment> defaults() {
        return List.of(
                new Environment("development", "Development", 0),
                new Environment("staging", "Staging", 1),
                new Environment("production", "Production", 2));
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
}
