package dev.flags.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flags.server.flag.Flag;
import dev.flags.server.flag.FlagRepository;
import dev.flags.server.support.IntegrationTest;
import dev.flags.server.support.TestClient.Response;
import dev.flags.server.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The claim: one tenant cannot read or change another's data, and that holds even when
 * the application code gets it wrong.
 */
class TenantIsolationIT extends IntegrationTest {

    private static final List<String> TENANT_TABLES =
            List.of("users", "environments", "flags", "flag_configs", "api_keys", "audit_events");

    @Autowired
    private FlagRepository flags;

    @Autowired
    private TransactionTemplate transactions;

    /** Runs SQL the way the application does: its role, its pool, inside a transaction for a tenant. */
    private <T> T asTenant(UUID tenantId, java.util.function.Function<JdbcTemplate, T> work) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (tenantId == null) {
            return transactions.execute(status -> work.apply(jdbc));
        }
        return TenantContext.callAs(tenantId, () -> transactions.execute(status -> work.apply(jdbc)));
    }

    @Test
    void oneTenantsFlagsDoNotExistForAnother() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(acme, "new-checkout");

        assertThat(http.get("/api/flags", globex.token()).body()).isEmpty();
        assertThat(http.get("/api/flags/new-checkout", globex.token()).status()).isEqualTo(404);
        assertThat(http.delete("/api/flags/new-checkout", globex.token()).status()).isEqualTo(404);
        Response write = http.put(
                "/api/flags/new-checkout/environments/production",
                globex.token(),
                Map.of("enabled", true, "rules", List.of(), "fallthroughPercentage", 100, "version", 0));
        assertThat(write.status()).isEqualTo(404);

        assertThat(http.get("/api/flags/new-checkout", acme.token()).status()).isEqualTo(200);
    }

    @Test
    void twoTenantsCanUseTheSameFlagKeyIndependently() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(acme, "dark-mode");
        createFlag(globex, "dark-mode");

        configure(acme, "dark-mode", "production", true, 100);

        Response globexFlag = http.get("/api/flags/dark-mode", globex.token());
        assertThat(globexFlag.body().get("environments")).allSatisfy(config -> assertThat(config.get("enabled").asBoolean())
                .isFalse());
    }

    @Test
    void aQueryWithNoTenantFilterStillReturnsOnlyTheCurrentTenantsRows() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(acme, "acme-one");
        createFlag(acme, "acme-two");
        createFlag(globex, "globex-one");

        // findAll() is "select * from flags". Nothing in the application narrows it.
        List<String> seenByAcme = TenantContext.callAs(
                acme.tenantId(), () -> transactions.execute(status -> flags.findAll().stream().map(Flag::getKey).toList()));
        Long countedByGlobex =
                asTenant(globex.tenantId(), jdbc -> jdbc.queryForObject("select count(*) from flags", Long.class));

        assertThat(seenByAcme).containsExactlyInAnyOrder("acme-one", "acme-two");
        assertThat(countedByGlobex).isEqualTo(1);
    }

    @Test
    void withNoTenantSetEveryTableLooksEmpty() {
        Account acme = signUp();
        createFlag(acme, "exists");

        for (String table : List.of("tenants", "users", "environments", "flags", "flag_configs", "audit_events")) {
            Long visible = asTenant(null, jdbc -> jdbc.queryForObject("select count(*) from " + table, Long.class));
            assertThat(visible).as("rows of %s visible with no tenant", table).isZero();
        }
    }

    @Test
    void aTenantCannotUpdateOrDeleteAnothersRowsEvenByNamingThem() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(globex, "target");

        Integer updated = asTenant(
                acme.tenantId(),
                jdbc -> jdbc.update("update flags set name = 'hijacked' where tenant_id = ?", globex.tenantId()));
        Integer deleted = asTenant(
                acme.tenantId(), jdbc -> jdbc.update("delete from flags where tenant_id = ?", globex.tenantId()));

        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(owner.queryForObject(
                        "select name from flags where tenant_id = ? and key = 'target'", String.class, globex.tenantId()))
                .isEqualTo("Flag target");
    }

    @Test
    void aTenantCannotInsertARowThatBelongsToAnother() {
        Account acme = signUp();
        Account globex = signUp();

        assertThatThrownBy(() -> asTenant(
                        acme.tenantId(),
                        jdbc -> jdbc.update(
                                "insert into flags (id, tenant_id, key, name, salt) values (?, ?, 'planted', 'Planted', 's')",
                                UUID.randomUUID(),
                                globex.tenantId())))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void aTenantCannotAttachItsOwnRowToAnothersByGuessingAnId() {
        // Foreign-key checks bypass row-level security, so a plain "flag_id references
        // flags" would accept another tenant's flag. The composite key does not.
        Account acme = signUp();
        Account globex = signUp();
        createFlag(globex, "theirs");
        UUID globexFlag = owner.queryForObject(
                "select id from flags where tenant_id = ? and key = 'theirs'", UUID.class, globex.tenantId());
        UUID acmeEnvironment = owner.queryForObject(
                "select id from environments where tenant_id = ? and key = 'production'", UUID.class, acme.tenantId());

        assertThatThrownBy(() -> asTenant(
                        acme.tenantId(),
                        jdbc -> jdbc.update(
                                "insert into flag_configs (id, tenant_id, flag_id, environment_id) values (?, ?, ?, ?)",
                                UUID.randomUUID(),
                                acme.tenantId(),
                                globexFlag,
                                acmeEnvironment)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("foreign key");
    }

    @Test
    void theTenantSettingDoesNotSurviveOnAPooledConnection() {
        Account acme = signUp();
        createFlag(acme, "visible-to-acme");

        // The pool hands the same few connections round. If the setting outlived its
        // transaction, some of these tenant-less reads would see Acme's flag.
        for (int i = 0; i < 50; i++) {
            Long asAcme = asTenant(acme.tenantId(), jdbc -> jdbc.queryForObject("select count(*) from flags", Long.class));
            Long asNobody = asTenant(null, jdbc -> jdbc.queryForObject("select count(*) from flags", Long.class));
            assertThat(asAcme).isEqualTo(1);
            assertThat(asNobody).isZero();
        }
    }

    @Test
    void anSdkKeyReadsOnlyItsOwnTenantsFlags() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(acme, "acme-flag");
        createFlag(globex, "globex-flag");
        String acmeKey = createKey(acme, "production");

        Response snapshot = http.get("/sdk/flags", acmeKey);

        assertThat(snapshot.status()).isEqualTo(200);
        assertThat(snapshot.body().get("flags")).hasSize(1);
        assertThat(snapshot.body().get("flags").get(0).get("key").asString()).isEqualTo("acme-flag");
    }

    @Test
    void theAuditLogCannotBeRewrittenByTheApplication() {
        Account acme = signUp();
        createFlag(acme, "audited");

        assertThatThrownBy(() -> asTenant(acme.tenantId(), jdbc -> jdbc.update("update audit_events set actor_email = 'nobody'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> asTenant(acme.tenantId(), jdbc -> jdbc.update("delete from audit_events")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void everyTableThatHoldsTenantDataIsProtected() {
        // Guards the next migration: a new table with a tenant_id and no policy fails here.
        List<String> unprotected = owner.queryForList(
                """
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relkind = 'r'
                  and exists (select 1 from pg_attribute a
                              where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
                  and (not c.relrowsecurity
                       or not exists (select 1 from pg_policy p where p.polrelid = c.oid))
                """,
                String.class);
        List<String> protectedTables = owner.queryForList(
                "select relname from pg_class where relrowsecurity and relnamespace = 'public'::regnamespace",
                String.class);

        assertThat(unprotected).isEmpty();
        assertThat(protectedTables).contains("tenants").containsAll(TENANT_TABLES);
    }

    @Test
    void theApplicationsRoleHasNoWayAroundThePolicies() throws SQLException {
        Map<String, Object> role = owner.queryForMap(
                "select rolsuper, rolbypassrls, rolcreaterole from pg_roles where rolname = ?", APP_USER);
        Long owned = owner.queryForObject(
                "select count(*) from pg_class c join pg_roles r on r.oid = c.relowner"
                        + " where r.rolname = ? and c.relnamespace = 'public'::regnamespace",
                Long.class,
                APP_USER);

        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        // A table's owner is exempt from its policies. The application owns nothing.
        assertThat(owned).isZero();
    }
}
