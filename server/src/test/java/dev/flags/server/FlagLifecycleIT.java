package dev.flags.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flags.server.support.IntegrationTest;
import dev.flags.server.support.TestClient.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FlagLifecycleIT extends IntegrationTest {

    private static Map<String, Object> rule(String id, int percentage, String attribute, String operator, String... values) {
        return Map.of(
                "id", id,
                "description", "",
                "conditions", List.of(Map.of("attribute", attribute, "operator", operator, "values", List.of(values))),
                "rolloutPercentage", percentage);
    }

    private long revision(Account account, String environment) {
        return owner.queryForObject(
                "select revision from environments where tenant_id = ? and key = ?",
                Long.class,
                account.tenantId(),
                environment);
    }

    private JsonNode audit(Account account) {
        return http.get("/api/audit", account.token()).body().get("items");
    }

    @Test
    void aNewFlagIsOffInEveryEnvironment() {
        Account admin = signUp();

        Response created = http.post(
                "/api/flags", admin.token(), Map.of("key", "new-checkout", "name", "New checkout", "description", "One-page flow"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("environments")).hasSize(3).allSatisfy(config -> {
            assertThat(config.get("enabled").asBoolean()).isFalse();
            assertThat(config.get("version").asLong()).isZero();
        });
        assertThat(created.body().get("environments").findValuesAsString("environment"))
                .containsExactly("development", "staging", "production");
    }

    @Test
    void aFlagKeyIsUniqueWithinAnOrganizationAndMustBeWellFormed() {
        Account admin = signUp();
        createFlag(admin, "taken");

        Response duplicate = http.post("/api/flags", admin.token(), Map.of("key", "taken", "name", "Again"));
        Response malformed = http.post("/api/flags", admin.token(), Map.of("key", "Has Spaces", "name", "Bad"));

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.body().get("fields").has("key")).isTrue();
    }

    @Test
    void savingAConfigurationStoresItAndMovesTheVersionOn() {
        Account admin = signUp();
        createFlag(admin, "pricing");

        Response saved = configure(
                admin, "pricing", "staging", true, 25, rule("pk", 100, "country", "IN", "PK", "AE"));

        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().get("version").asLong()).isEqualTo(1);
        JsonNode staging = http.get("/api/flags/pricing", admin.token()).body().get("environments").get(1);
        assertThat(staging.get("enabled").asBoolean()).isTrue();
        assertThat(staging.get("fallthroughPercentage").asInt()).isEqualTo(25);
        assertThat(staging.get("rules").get(0).get("conditions").get(0).get("values").toString())
                .isEqualTo("[\"PK\",\"AE\"]");
        // Other environments are untouched.
        assertThat(http.get("/api/flags/pricing", admin.token()).body().get("environments").get(2).get("enabled").asBoolean())
                .isFalse();
    }

    @Test
    void anEditBasedOnAnOutOfDateVersionIsRefused() {
        Account admin = signUp();
        createFlag(admin, "banner");
        Map<String, Object> basedOnVersionZero =
                Map.of("enabled", true, "rules", List.of(), "fallthroughPercentage", 10, "version", 0);
        assertThat(http.put("/api/flags/banner/environments/production", admin.token(), basedOnVersionZero)
                        .status())
                .isEqualTo(200);

        // A second browser tab, still showing version 0, tries to save something else.
        Response stale = http.put(
                "/api/flags/banner/environments/production",
                admin.token(),
                Map.of("enabled", false, "rules", List.of(), "fallthroughPercentage", 90, "version", 0));

        assertThat(stale.status()).isEqualTo(409);
        JsonNode production = http.get("/api/flags/banner", admin.token()).body().get("environments").get(2);
        assertThat(production.get("fallthroughPercentage").asInt()).isEqualTo(10);
    }

    @Test
    void ofManySimultaneousEditsToOneVersionExactlyOneWins() throws Exception {
        Account admin = signUp();
        createFlag(admin, "contested");
        int editors = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(editors)) {
            for (int i = 0; i < editors; i++) {
                int percentage = 10 + i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return http.put(
                                    "/api/flags/contested/environments/production",
                                    admin.token(),
                                    Map.of("enabled", true, "rules", List.of(), "fallthroughPercentage", percentage, "version", 0))
                            .status();
                }));
            }
            start.countDown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> outcome : outcomes) {
            statuses.add(outcome.get());
        }
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(editors - 1);
        assertThat(currentVersion(admin, "contested", "production")).isEqualTo(1);
        // One winner, one audit entry, one revision.
        assertThat(audit(admin).findValuesAsString("action")).containsOnlyOnce("FLAG_CONFIG_UPDATED");
    }

    @Test
    void anInvalidConfigurationIsRejectedWithAReasonAndChangesNothing() {
        Account admin = signUp();
        createFlag(admin, "strict");
        long before = revision(admin, "production");

        Response tooMuch = configure(admin, "strict", "production", true, 140);
        Response emptyRule = http.put(
                "/api/flags/strict/environments/production",
                admin.token(),
                Map.of(
                        "enabled", true,
                        "rules", List.of(Map.of("id", "r", "description", "", "conditions", List.of(), "rolloutPercentage", 100)),
                        "fallthroughPercentage", 100,
                        "version", 0));
        Response unknownOperator =
                configure(admin, "strict", "production", true, 100, rule("r", 100, "plan", "RESEMBLES", "pro"));
        Response duplicateIds = configure(
                admin,
                "strict",
                "production",
                true,
                100,
                rule("same", 100, "plan", "IN", "pro"),
                rule("same", 0, "plan", "IN", "free"));

        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(emptyRule.status()).isEqualTo(400);
        assertThat(emptyRule.body().get("detail").asString()).contains("at least one condition");
        assertThat(unknownOperator.status()).isEqualTo(400);
        assertThat(duplicateIds.status()).isEqualTo(400);
        assertThat(currentVersion(admin, "strict", "production")).isZero();
        assertThat(revision(admin, "production")).isEqualTo(before);
    }

    @Test
    void savingWithoutChangingAnythingIsNotAChange() {
        Account admin = signUp();
        createFlag(admin, "steady");
        configure(admin, "steady", "production", true, 40);
        long revision = revision(admin, "production");
        int entries = audit(admin).size();

        Response again = configure(admin, "steady", "production", true, 40);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("version").asLong()).isEqualTo(1);
        assertThat(revision(admin, "production")).isEqualTo(revision);
        assertThat(audit(admin)).hasSize(entries);
    }

    @Test
    void everyChangeIsRecordedWithWhoMadeItAndWhatItWasBefore() {
        Account admin = signUp();
        Account editor = addMember(admin, "EDITOR");
        createFlag(admin, "tracked");
        configure(editor, "tracked", "production", true, 20);

        JsonNode latest = audit(admin).get(0);

        assertThat(latest.get("action").asString()).isEqualTo("FLAG_CONFIG_UPDATED");
        assertThat(latest.get("actor").asString()).isEqualTo(editor.email());
        assertThat(latest.get("target").asString()).isEqualTo("tracked");
        assertThat(latest.get("environment").asString()).isEqualTo("production");
        assertThat(latest.get("before").get("enabled").asBoolean()).isFalse();
        assertThat(latest.get("before").get("fallthroughPercentage").asInt()).isEqualTo(100);
        assertThat(latest.get("after").get("enabled").asBoolean()).isTrue();
        assertThat(latest.get("after").get("fallthroughPercentage").asInt()).isEqualTo(20);
        assertThat(audit(admin).findValuesAsString("action"))
                .containsExactly("FLAG_CONFIG_UPDATED", "FLAG_CREATED", "MEMBER_ADDED", "ORGANIZATION_CREATED");
    }

    @Test
    void aRefusedChangeLeavesNoAuditEntry() {
        Account admin = signUp();
        createFlag(admin, "quiet");
        int entries = audit(admin).size();

        http.put(
                "/api/flags/quiet/environments/production",
                admin.token(),
                Map.of("enabled", true, "rules", List.of(), "fallthroughPercentage", 100, "version", 7));

        assertThat(audit(admin)).hasSize(entries);
    }

    @Test
    void aDraftCanBePreviewedWithoutSavingIt() {
        Account admin = signUp();
        createFlag(admin, "preview-me");
        List<Map<String, Object>> contexts = List.of(
                Map.of("key", "u1", "attributes", Map.of("country", "PK")),
                Map.of("key", "u2", "attributes", Map.of("country", "US")));

        Response preview = http.post(
                "/api/flags/preview-me/preview",
                admin.token(),
                Map.of(
                        "enabled", true,
                        "rules", List.of(rule("pk", 100, "country", "IN", "PK")),
                        "fallthroughPercentage", 0,
                        "contexts", contexts));

        assertThat(preview.status()).isEqualTo(200);
        JsonNode evaluations = preview.body().get("evaluations");
        assertThat(evaluations.get(0).get("value").asBoolean()).isTrue();
        assertThat(evaluations.get(0).get("reason").asString()).isEqualTo("RULE_MATCH");
        assertThat(evaluations.get(1).get("value").asBoolean()).isFalse();
        assertThat(evaluations.get(1).get("reason").asString()).isEqualTo("FALLTHROUGH");
        // Nothing was stored.
        assertThat(currentVersion(admin, "preview-me", "production")).isZero();
    }

    @Test
    void deletingAFlagRemovesItEverywhereAndIsRecorded() {
        Account admin = signUp();
        createFlag(admin, "short-lived");
        long before = revision(admin, "staging");

        assertThat(http.delete("/api/flags/short-lived", admin.token()).status()).isEqualTo(204);

        assertThat(http.get("/api/flags/short-lived", admin.token()).status()).isEqualTo(404);
        assertThat(owner.queryForObject(
                        "select count(*) from flag_configs where tenant_id = ?", Long.class, admin.tenantId()))
                .isZero();
        assertThat(revision(admin, "staging")).isEqualTo(before + 1);
        assertThat(audit(admin).get(0).get("action").asString()).isEqualTo("FLAG_DELETED");
    }

    @Test
    void renamingAFlagDoesNotPublishAnythingToSdks() {
        Account admin = signUp();
        createFlag(admin, "renamed");
        long before = revision(admin, "production");

        Response renamed = http.put(
                "/api/flags/renamed", admin.token(), Map.of("name", "A better name", "description", "Now described"));

        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("name").asString()).isEqualTo("A better name");
        assertThat(revision(admin, "production")).isEqualTo(before);
    }

    @Test
    void theAuditLogIsPagedNewestFirst() {
        Account admin = signUp();
        for (int i = 0; i < 5; i++) {
            createFlag(admin, "flag-" + i);
        }

        JsonNode first = http.get("/api/audit?page=0&size=2", admin.token()).body();
        JsonNode last = http.get("/api/audit?page=2&size=2", admin.token()).body();

        assertThat(first.get("total").asLong()).isEqualTo(6);
        assertThat(first.get("items").findValuesAsString("target")).containsExactly("flag-4", "flag-3");
        assertThat(last.get("items").findValuesAsString("target").getFirst()).isEqualTo("flag-0");
        assertThat(http.get("/api/audit?target=flag-2", admin.token()).body().get("items")).hasSize(1);
    }
}
