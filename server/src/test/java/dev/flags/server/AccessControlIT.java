package dev.flags.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flags.server.support.IntegrationTest;
import dev.flags.server.support.TestClient.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class AccessControlIT extends IntegrationTest {

    private static final Map<String, Object> NEW_FLAG = Map.of("key", "a-flag", "name", "A flag");

    private UUID idOf(Account member) {
        return owner.queryForObject("select id from users where email = ?", UUID.class, member.email());
    }

    private static String tokenSignedWith(String secret, UUID userId, UUID tenantId, Instant expiresAt) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim("tid", tenantId.toString())
                .issuedAt(expiresAt.minusSeconds(3600))
                .expiresAt(expiresAt)
                .build();
        return NimbusJwtEncoder.withSecretKey(key)
                .build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    void theConsoleApiNeedsAValidToken() {
        Account admin = signUp();
        UUID adminId = idOf(admin);
        Instant inAnHour = Instant.now().plusSeconds(3600);

        assertThat(http.get("/api/flags", null).status()).isEqualTo(401);
        assertThat(http.get("/api/flags", "not-a-token").status()).isEqualTo(401);
        assertThat(http.get(
                                "/api/flags",
                                tokenSignedWith("some-other-signing-key-0123456789abcdef", adminId, admin.tenantId(), inAnHour))
                        .status())
                .as("a token signed with a different key")
                .isEqualTo(401);
        assertThat(http.get(
                                "/api/flags",
                                tokenSignedWith(JWT_SECRET, adminId, admin.tenantId(), Instant.now().minusSeconds(120)))
                        .status())
                .as("an expired token")
                .isEqualTo(401);

        // The same forging helper with the real key and a live expiry is accepted, so the
        // rejections above are about the key and the expiry and nothing else.
        assertThat(http.get("/api/flags", tokenSignedWith(JWT_SECRET, adminId, admin.tenantId(), inAnHour)).status())
                .isEqualTo(200);
    }

    @Test
    void aValidTokenCannotBePointedAtAnotherTenant() {
        Account acme = signUp();
        Account globex = signUp();
        createFlag(globex, "globex-flag");

        // Acme's real user id, Globex's tenant id, correctly signed. The user does not
        // exist in that tenant, so there is nobody to authenticate.
        String crossed = tokenSignedWith(JWT_SECRET, idOf(acme), globex.tenantId(), Instant.now().plusSeconds(3600));

        assertThat(http.get("/api/flags", crossed).status()).isEqualTo(401);
    }

    @Test
    void aViewerCanReadButNotChange() {
        Account admin = signUp();
        createFlag(admin, "existing");
        Account viewer = addMember(admin, "VIEWER");

        assertThat(http.get("/api/flags", viewer.token()).status()).isEqualTo(200);
        assertThat(http.get("/api/audit", viewer.token()).status()).isEqualTo(200);

        assertThat(http.post("/api/flags", viewer.token(), NEW_FLAG).status()).isEqualTo(403);
        assertThat(http.delete("/api/flags/existing", viewer.token()).status()).isEqualTo(403);
        Response configure = http.put(
                "/api/flags/existing/environments/production",
                viewer.token(),
                Map.of("enabled", true, "rules", List.of(), "fallthroughPercentage", 100, "version", 0));
        assertThat(configure.status()).isEqualTo(403);
        assertThat(http.get("/api/keys", viewer.token()).status()).isEqualTo(403);
        assertThat(http.get("/api/members", viewer.token()).status()).isEqualTo(403);
    }

    @Test
    void anEditorCanChangeFlagsButNotKeysOrMembers() {
        Account admin = signUp();
        Account editor = addMember(admin, "EDITOR");

        assertThat(http.post("/api/flags", editor.token(), NEW_FLAG).status()).isEqualTo(201);
        assertThat(configure(editor, "a-flag", "staging", true, 50).status()).isEqualTo(200);

        assertThat(http.post("/api/keys", editor.token(), Map.of("name", "k", "environment", "production"))
                        .status())
                .isEqualTo(403);
        assertThat(http.post(
                                "/api/members",
                                editor.token(),
                                Map.of("email", "x@example.com", "password", "correct horse", "role", "ADMIN"))
                        .status())
                .isEqualTo(403);
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestNotWhenTheTokenExpires() {
        Account admin = signUp();
        Account editor = addMember(admin, "EDITOR");
        assertThat(http.post("/api/flags", editor.token(), NEW_FLAG).status()).isEqualTo(201);

        Response demoted = http.put("/api/members/" + idOf(editor) + "/role", admin.token(), Map.of("role", "VIEWER"));

        assertThat(demoted.status()).isEqualTo(200);
        // Same token as before.
        assertThat(http.delete("/api/flags/a-flag", editor.token()).status()).isEqualTo(403);
        assertThat(http.get("/api/flags", editor.token()).status()).isEqualTo(200);
    }

    @Test
    void aRemovedMembersTokenStopsWorkingAtOnce() {
        Account admin = signUp();
        Account editor = addMember(admin, "EDITOR");
        assertThat(http.get("/api/flags", editor.token()).status()).isEqualTo(200);

        assertThat(http.delete("/api/members/" + idOf(editor), admin.token()).status()).isEqualTo(204);

        assertThat(http.get("/api/flags", editor.token()).status()).isEqualTo(401);
    }

    @Test
    void anOrganizationAlwaysKeepsOneAdministrator() {
        Account admin = signUp();
        UUID adminId = idOf(admin);

        Response demoteSelf = http.put("/api/members/" + adminId + "/role", admin.token(), Map.of("role", "EDITOR"));
        Response removeSelf = http.delete("/api/members/" + adminId, admin.token());

        assertThat(demoteSelf.status()).isEqualTo(409);
        assertThat(removeSelf.status()).isEqualTo(409);

        // With a second administrator, the first can step down.
        addMember(admin, "ADMIN");
        assertThat(http.put("/api/members/" + adminId + "/role", admin.token(), Map.of("role", "EDITOR"))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void loginDoesNotRevealWhetherAnEmailIsRegistered() {
        Account admin = signUp();

        Response wrongPassword =
                http.post("/api/auth/login", null, Map.of("email", admin.email(), "password", "not the password"));
        Response unknownEmail =
                http.post("/api/auth/login", null, Map.of("email", "nobody@example.com", "password", "not the password"));

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.body().get("detail")).isEqualTo(unknownEmail.body().get("detail"));
    }

    @Test
    void loginAcceptsTheRightPasswordWhateverTheCaseOfTheEmail() {
        Account admin = signUp();

        Response login = http.post(
                "/api/auth/login", null, Map.of("email", admin.email().toUpperCase(), "password", "correct horse"));

        assertThat(login.status()).isEqualTo(200);
        assertThat(http.get("/api/session", login.body().get("token").asString()).body().get("user").get("email").asString())
                .isEqualTo(admin.email());
    }

    @Test
    void anEmailCanBelongToOnlyOneOrganization() {
        Account acme = signUp();

        Response again = http.post(
                "/api/auth/signup", null, Map.of("organization", "Copy", "email", acme.email(), "password", "correct horse"));

        assertThat(again.status()).isEqualTo(409);
        // The failed signup left nothing behind.
        assertThat(owner.queryForObject("select count(*) from tenants where name = 'Copy'", Long.class))
                .isZero();
    }

    @Test
    void signupRejectsAWeakPasswordAndSaysWhichFieldIsWrong() {
        Response response = http.post(
                "/api/auth/signup", null, Map.of("organization", "Acme", "email", "not-an-email", "password", "short"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().get("fields").has("email")).isTrue();
        assertThat(response.body().get("fields").has("password")).isTrue();
    }

    @Test
    void consoleTokensAndSdkKeysAreNotInterchangeable() {
        Account admin = signUp();
        String key = createKey(admin, "production");

        assertThat(http.get("/sdk/flags", null).status()).isEqualTo(401);
        assertThat(http.get("/sdk/flags", "ffk_this-key-was-never-issued").status()).isEqualTo(401);
        assertThat(http.get("/sdk/flags", admin.token()).status()).isEqualTo(401);
        assertThat(http.get("/api/flags", key).status()).isEqualTo(401);

        assertThat(http.get("/sdk/flags", key).status()).isEqualTo(200);
    }

    @Test
    void passwordsAndApiKeysAreStoredOnlyAsHashes() {
        Account admin = signUp();
        String key = createKey(admin, "production");

        String storedPassword =
                owner.queryForObject("select password_hash from users where email = ?", String.class, admin.email());
        Map<String, Object> storedKey = owner.queryForMap(
                "select prefix, key_hash from api_keys where tenant_id = ?", admin.tenantId());

        assertThat(storedPassword).startsWith("$2").doesNotContain("correct horse");
        assertThat((String) storedKey.get("key_hash")).hasSize(64).isNotEqualTo(key);
        assertThat(key).startsWith((String) storedKey.get("prefix"));
        // The visible prefix is a small part of the key, not most of it.
        assertThat(((String) storedKey.get("prefix")).length()).isLessThan(key.length() / 4);
        assertThat(http.get("/api/keys", admin.token()).body().toString()).doesNotContain(key);
    }
}
