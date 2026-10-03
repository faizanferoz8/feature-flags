package dev.flags.server.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the whole application on a real port against a real Postgres. Row-level security,
 * LISTEN/NOTIFY and transaction isolation are the subject of these tests, and none of
 * them exists in an in-memory substitute.
 *
 * <p>Postgres comes from Testcontainers. Where Docker is not available, point the tests
 * at an existing server instead by setting FLAGS_TEST_DB_URL along with
 * FLAGS_TEST_DB_OWNER_USER and FLAGS_TEST_DB_OWNER_PASSWORD; the owner must be allowed
 * to create roles.
 *
 * <p>Tests share one application and one database, and never clean up. Each one signs up
 * its own organizations, and tenant isolation is what keeps them out of each other's
 * way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String APP_USER = "flags_app";
    protected static final String APP_PASSWORD = "flags_app_test";
    protected static final String JWT_SECRET = "test-only-signing-key-0123456789abcdef";

    private static final String URL;
    private static final String OWNER_USER;
    private static final String OWNER_PASSWORD;

    static {
        String external = System.getenv("FLAGS_TEST_DB_URL");
        if (external != null) {
            URL = external;
            OWNER_USER = System.getenv("FLAGS_TEST_DB_OWNER_USER");
            OWNER_PASSWORD = System.getenv("FLAGS_TEST_DB_OWNER_PASSWORD");
        } else {
            // Started once and shared by every test class; Testcontainers removes it when
            // the JVM exits.
            PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
            postgres.start();
            URL = postgres.getJdbcUrl();
            OWNER_USER = postgres.getUsername();
            OWNER_PASSWORD = postgres.getPassword();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("flags.jwt.secret", () -> JWT_SECRET);
        registry.add("flags.stream.heartbeat", () -> "1s");
    }

    @Value("${local.server.port}")
    private int port;

    /** The application's own pool: connects as the unprivileged role. */
    @Autowired
    protected DataSource dataSource;

    protected TestClient http;

    /** Connects as the owner, which bypasses row-level security. For looking behind the curtain. */
    protected JdbcTemplate owner;

    @BeforeEach
    void connect() {
        http = new TestClient("http://localhost:" + port);
        owner = new JdbcTemplate(new DriverManagerDataSource(URL, OWNER_USER, OWNER_PASSWORD));
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** A separate connection as the application's role, outside the application. */
    protected static Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(URL, APP_USER, APP_PASSWORD);
    }

    protected Account signUp() {
        return signUp("Org " + UUID.randomUUID().toString().substring(0, 8));
    }

    protected Account signUp(String organization) {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        TestClient.Response response = http.post(
                "/api/auth/signup",
                null,
                java.util.Map.of("organization", organization, "email", email, "password", "correct horse"));
        if (response.status() != 201) {
            throw new AssertionError("Signup failed: " + response);
        }
        UUID tenantId = owner.queryForObject("select tenant_id from users where email = ?", UUID.class, email);
        return new Account(response.body().get("token").asString(), email, tenantId);
    }

    /** Adds a member with the given role and returns their signed-in account. */
    protected Account addMember(Account admin, String role) {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        TestClient.Response added = http.post(
                "/api/members", admin.token(), java.util.Map.of("email", email, "password", "correct horse", "role", role));
        if (added.status() != 201) {
            throw new AssertionError("Adding a member failed: " + added);
        }
        TestClient.Response login =
                http.post("/api/auth/login", null, java.util.Map.of("email", email, "password", "correct horse"));
        return new Account(login.body().get("token").asString(), email, admin.tenantId());
    }

    protected void createFlag(Account account, String key) {
        TestClient.Response response =
                http.post("/api/flags", account.token(), java.util.Map.of("key", key, "name", "Flag " + key));
        if (response.status() != 201) {
            throw new AssertionError("Creating a flag failed: " + response);
        }
    }

    /** Saves a configuration on top of whatever version is current. Returns the response. */
    protected TestClient.Response configure(
            Account account, String flagKey, String environment, boolean enabled, int percentage, Object... rules) {
        long version = currentVersion(account, flagKey, environment);
        return http.put(
                "/api/flags/" + flagKey + "/environments/" + environment,
                account.token(),
                java.util.Map.of(
                        "enabled", enabled,
                        "rules", java.util.List.of(rules),
                        "fallthroughPercentage", percentage,
                        "version", version));
    }

    protected long currentVersion(Account account, String flagKey, String environment) {
        for (var config : http.get("/api/flags/" + flagKey, account.token()).body().get("environments")) {
            if (config.get("environment").asString().equals(environment)) {
                return config.get("version").asLong();
            }
        }
        throw new AssertionError("No environment " + environment + " on flag " + flagKey);
    }

    /** Creates an SDK key and returns the secret. */
    protected String createKey(Account admin, String environment) {
        TestClient.Response response =
                http.post("/api/keys", admin.token(), java.util.Map.of("name", "test", "environment", environment));
        if (response.status() != 201) {
            throw new AssertionError("Creating a key failed: " + response);
        }
        return response.body().get("secret").asString();
    }

    public record Account(String token, String email, UUID tenantId) {}
}
