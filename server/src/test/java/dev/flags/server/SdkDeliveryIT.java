package dev.flags.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.flags.core.EvaluationContext;
import dev.flags.sdk.FlagClient;
import dev.flags.server.support.IntegrationTest;
import dev.flags.server.support.TestClient.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The claim: a committed change reaches every connected SDK within moments, a change
 * that did not commit reaches none, and what an SDK decides locally is what the server
 * would have decided.
 */
class SdkDeliveryIT extends IntegrationTest {

    private static final Duration SOON = Duration.ofSeconds(5);
    private static final EvaluationContext USER = EvaluationContext.of("user-1");

    private FlagClient connect(String key) throws InterruptedException {
        FlagClient client = FlagClient.connect(URI.create(baseUrl()), key);
        assertThat(client.awaitReady(SOON)).as("the SDK received its first rules").isTrue();
        return client;
    }

    @Test
    void anSdkSeesAChangeMomentsAfterItIsSaved() throws Exception {
        Account admin = signUp();
        createFlag(admin, "new-checkout");
        try (FlagClient sdk = connect(createKey(admin, "production"))) {
            assertThat(sdk.isEnabled("new-checkout", USER, true)).isFalse();

            configure(admin, "new-checkout", "production", true, 100);

            await().atMost(SOON).until(() -> sdk.isEnabled("new-checkout", USER, false));

            configure(admin, "new-checkout", "production", false, 100);

            await().atMost(SOON).until(() -> !sdk.isEnabled("new-checkout", USER, true));
        }
    }

    @Test
    void aChangeInOneEnvironmentDoesNotReachAnother() throws Exception {
        Account admin = signUp();
        createFlag(admin, "beta");
        try (FlagClient staging = connect(createKey(admin, "staging"));
                FlagClient production = connect(createKey(admin, "production"))) {
            long productionRevision = production.revision();

            configure(admin, "beta", "staging", true, 100);

            await().atMost(SOON).until(() -> staging.isEnabled("beta", USER, false));
            assertThat(production.isEnabled("beta", USER, false)).isFalse();
            assertThat(production.revision()).isEqualTo(productionRevision);
        }
    }

    @Test
    void aNewFlagAndADeletedFlagBothReachTheSdk() throws Exception {
        Account admin = signUp();
        try (FlagClient sdk = connect(createKey(admin, "production"))) {
            assertThat(sdk.evaluate("later", USER, true).reason().name()).isEqualTo("FLAG_NOT_FOUND");

            createFlag(admin, "later");
            await().atMost(SOON).until(() -> sdk.evaluate("later", USER, true).reason().name().equals("OFF"));

            http.delete("/api/flags/later", admin.token());
            await().atMost(SOON).until(() -> sdk.evaluate("later", USER, true).reason().name().equals("FLAG_NOT_FOUND"));
        }
    }

    /**
     * Writes straight to the database, as a second instance of the application would,
     * so the instance under test can only learn of the change from Postgres.
     */
    private void changeFromAnotherInstance(Account account, String flagKey, boolean enabled, boolean commit) throws Exception {
        try (Connection other = connectAsApplication()) {
            other.setAutoCommit(false);
            try (PreparedStatement tenant = other.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                tenant.setString(1, account.tenantId().toString());
                tenant.execute();
            }
            UUID environmentId;
            try (PreparedStatement find = other.prepareStatement("select id from environments where key = 'production'");
                    var row = find.executeQuery()) {
                row.next();
                environmentId = row.getObject(1, UUID.class);
            }
            try (PreparedStatement update = other.prepareStatement(
                    "update flag_configs set enabled = ?, version = version + 1 where environment_id = ?"
                            + " and flag_id = (select id from flags where key = ?)")) {
                update.setBoolean(1, enabled);
                update.setObject(2, environmentId);
                update.setString(3, flagKey);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement bump =
                    other.prepareStatement("update environments set revision = revision + 1 where id = ?")) {
                bump.setObject(1, environmentId);
                bump.executeUpdate();
            }
            try (PreparedStatement notify = other.prepareStatement("select pg_notify('flag_changes', ?)")) {
                notify.setString(1, "SNAPSHOT|" + account.tenantId() + "|" + environmentId);
                notify.execute();
            }
            if (commit) {
                other.commit();
            } else {
                other.rollback();
            }
        }
    }

    @Test
    void aChangeCommittedByAnotherInstanceReachesThisInstancesSdks() throws Exception {
        Account admin = signUp();
        createFlag(admin, "cross-instance");
        String key = createKey(admin, "production");
        try (FlagClient sdk = connect(key)) {
            changeFromAnotherInstance(admin, "cross-instance", true, true);

            await().atMost(SOON).until(() -> sdk.isEnabled("cross-instance", USER, false));
            // The polling endpoint is served from the same cache, so it has moved on too.
            assertThat(http.get("/sdk/flags", key).body().get("flags").get(0).get("enabled").asBoolean())
                    .isTrue();
        }
    }

    @Test
    void aChangeThatRolledBackIsNeverAnnounced() throws Exception {
        Account admin = signUp();
        createFlag(admin, "never-happened");
        try (FlagClient sdk = connect(createKey(admin, "production"))) {
            long revision = sdk.revision();
            List<Long> announced = new CopyOnWriteArrayList<>();
            sdk.onChange(snapshot -> announced.add(snapshot.revision()));

            changeFromAnotherInstance(admin, "never-happened", true, false);
            // A committed change afterwards proves the channel was working throughout.
            changeFromAnotherInstance(admin, "never-happened", false, true);

            await().atMost(SOON).until(() -> sdk.revision() == revision + 1);
            assertThat(announced).containsExactly(revision + 1);
            assertThat(sdk.isEnabled("never-happened", USER, true)).isFalse();
        }
    }

    @Test
    void aBurstOfChangesLeavesEverySdkOnTheLastOneWithoutGoingBackwards() throws Exception {
        Account admin = signUp();
        createFlag(admin, "busy");
        String key = createKey(admin, "production");
        try (FlagClient first = connect(key);
                FlagClient second = connect(key)) {
            List<Long> seen = new CopyOnWriteArrayList<>();
            first.onChange(snapshot -> seen.add(snapshot.revision()));
            long start = first.revision();

            for (int i = 1; i <= 30; i++) {
                assertThat(configure(admin, "busy", "production", true, i).status()).isEqualTo(200);
            }

            await().atMost(SOON).until(() -> first.revision() == start + 30 && second.revision() == start + 30);
            assertThat(seen).isSorted().doesNotHaveDuplicates();
            Response polled = http.get("/sdk/flags", key);
            assertThat(polled.body().get("flags").get(0).get("fallthroughPercentage").asInt()).isEqualTo(30);
        }
    }

    @Test
    void theSdkAndTheServerAgreeOnEveryUser() throws Exception {
        Account admin = signUp();
        createFlag(admin, "half");
        Map<String, Object> proRule = Map.of(
                "id", "pro",
                "description", "",
                "conditions", List.of(Map.of("attribute", "plan", "operator", "IN", "values", List.of("pro"))),
                "rolloutPercentage", 70);
        String key = createKey(admin, "production");
        configure(admin, "half", "production", true, 35, proRule);

        try (FlagClient sdk = connect(key)) {
            await().atMost(SOON).until(() -> sdk.evaluate("half", USER, false).reason().name().equals("FALLTHROUGH"));
            int on = 0;
            for (int i = 0; i < 300; i++) {
                Map<String, String> attributes = Map.of("plan", i % 3 == 0 ? "pro" : "free");
                EvaluationContext context = EvaluationContext.of("user-" + i, attributes);

                boolean local = sdk.isEnabled("half", context, false);
                Response remote = http.post(
                        "/sdk/evaluate", key, Map.of("context", Map.of("key", context.key(), "attributes", attributes)));

                assertThat(remote.body().get("flags").get("half").get("value").asBoolean())
                        .as("server and SDK on user-%d", i)
                        .isEqualTo(local);
                on += local ? 1 : 0;
            }
            // 100 pro users at 70% and 200 others at 35%: about 140.
            assertThat(on).isBetween(105, 175);
        }
    }

    @Test
    void wideningARolloutKeepsEveryoneWhoAlreadyHadTheFeature() throws Exception {
        Account admin = signUp();
        createFlag(admin, "gradual");
        List<EvaluationContext> users =
                IntStream.range(0, 500).mapToObj(i -> EvaluationContext.of("user-" + i)).toList();
        try (FlagClient sdk = connect(createKey(admin, "production"))) {
            Set<String> previous = new HashSet<>();
            for (int percentage : new int[] {5, 20, 50, 100}) {
                long before = sdk.revision();
                configure(admin, "gradual", "production", true, percentage);
                await().atMost(SOON).until(() -> sdk.revision() > before);

                Set<String> now = users.stream()
                        .filter(user -> sdk.isEnabled("gradual", user, false))
                        .map(EvaluationContext::key)
                        .collect(Collectors.toSet());

                assertThat(now).as("users with the feature at %d%%", percentage).containsAll(previous);
                assertThat(now.size()).isBetween(percentage * 5 - 40, percentage * 5 + 40);
                previous = now;
            }
            assertThat(previous).hasSize(500);
        }
    }

    @Test
    void pollingClientsAreToldWhenNothingHasChanged() {
        Account admin = signUp();
        createFlag(admin, "polled");
        String key = createKey(admin, "production");

        Response first = http.get("/sdk/flags", key);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        Response unchanged = http.get("/sdk/flags", key, Map.of("If-None-Match", etag));
        configure(admin, "polled", "production", true, 100);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("environment").asString()).isEqualTo("production");
        assertThat(unchanged.status()).isEqualTo(304);
        await().atMost(SOON).untilAsserted(() -> {
            Response changed = http.get("/sdk/flags", key, Map.of("If-None-Match", etag));
            assertThat(changed.status()).isEqualTo(200);
            assertThat(changed.body().get("revision").asLong()).isEqualTo(first.body().get("revision").asLong() + 1);
        });
    }

    @Test
    void theSnapshotSentToSdksCarriesNoInternalIdentifiers() {
        Account admin = signUp();
        createFlag(admin, "lean");
        String key = createKey(admin, "production");

        String snapshot = http.get("/sdk/flags", key).body().toString();

        assertThat(snapshot).doesNotContain(admin.tenantId().toString()).doesNotContain("tenantId");
    }

    @Test
    void revokingAKeyCutsOffItsOpenStreamAndItsNextRequest() throws Exception {
        Account admin = signUp();
        createFlag(admin, "guarded");
        String key = createKey(admin, "production");
        String keyId = http.get("/api/keys", admin.token()).body().get(0).get("id").asString();
        try (FlagClient sdk = connect(key)) {
            assertThat(http.delete("/api/keys/" + keyId, admin.token()).status()).isEqualTo(204);

            // The stream is closed by the server; the SDK reconnects, is refused, and gives up.
            await().atMost(Duration.ofSeconds(10)).until(sdk::isRejected);
            assertThat(http.get("/sdk/flags", key).status()).isEqualTo(401);

            // It keeps answering from the last rules it had rather than failing its caller.
            assertThat(sdk.evaluate("guarded", USER, true).reason().name()).isEqualTo("OFF");
        }
        assertThat(http.get("/api/keys", admin.token()).body().get(0).get("revokedAt").isNull()).isFalse();
    }

    @Test
    void usingAKeyRecordsWhenItWasLastUsed() {
        Account admin = signUp();
        String key = createKey(admin, "production");
        assertThat(http.get("/api/keys", admin.token()).body().get(0).get("lastUsedAt").isNull()).isTrue();

        http.get("/sdk/flags", key);

        assertThat(http.get("/api/keys", admin.token()).body().get(0).get("lastUsedAt").isNull()).isFalse();
    }
}
