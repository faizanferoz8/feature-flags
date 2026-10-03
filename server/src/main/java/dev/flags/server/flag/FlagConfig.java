package dev.flags.server.flag;

import dev.flags.core.FlagDefinition;
import dev.flags.core.Rule;
import dev.flags.server.tenancy.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** How one flag behaves in one environment. */
@Entity
@Table(name = "flag_configs")
public class FlagConfig extends TenantOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flag_id", nullable = false, updatable = false)
    private Flag flag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "environment_id", nullable = false, updatable = false)
    private Environment environment;

    @Column(nullable = false)
    private boolean enabled;

    @Convert(converter = RulesConverter.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private List<Rule> rules = List.of();

    @Column(name = "fallthrough_percentage", nullable = false)
    private int fallthroughPercentage = 100;

    /**
     * Optimistic lock. The console sends back the version it loaded; an edit based on a
     * version that is no longer current is refused instead of silently undoing whatever
     * was saved in between.
     */
    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected FlagConfig() {}

    FlagConfig(Flag flag, Environment environment) {
        this.flag = flag;
        this.environment = environment;
    }

    void update(boolean enabled, List<Rule> rules, int fallthroughPercentage) {
        this.enabled = enabled;
        this.rules = List.copyOf(rules);
        this.fallthroughPercentage = fallthroughPercentage;
        this.updatedAt = Instant.now();
    }

    boolean matches(boolean enabled, List<Rule> rules, int fallthroughPercentage) {
        return this.enabled == enabled
                && this.fallthroughPercentage == fallthroughPercentage
                && this.rules.equals(rules);
    }

    public FlagDefinition toDefinition() {
        return new FlagDefinition(flag.getKey(), flag.getSalt(), enabled, rules, fallthroughPercentage);
    }

    public Flag getFlag() {
        return flag;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public int getFallthroughPercentage() {
        return fallthroughPercentage;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
