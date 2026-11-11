package com.transactionhub.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountApiIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.7-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private AccountRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void clearAccounts() {
        repository.deleteAll();
    }

    @Test
    void createsAndRetrievesPersistedAccount() throws Exception {
        HttpResponse<String> created = post("{\"ownerName\":\"  Alex Smith  \",\"currency\":\"AUD\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.id");
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(JsonPath.<String>read(created.body(), "$.ownerName")).isEqualTo("Alex Smith");
        assertThat(JsonPath.<String>read(created.body(), "$.currency")).isEqualTo("AUD");
        assertThat(JsonPath.<Number>read(created.body(), "$.balance").doubleValue()).isZero();
        assertThat(JsonPath.<String>read(created.body(), "$.createdAt")).isNotBlank();
        assertThat(created.headers().firstValue("Location")).contains("/api/accounts/" + id);

        HttpResponse<String> retrieved = get("/api/accounts/" + id);
        assertThat(retrieved.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(retrieved.body(), "$.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(retrieved.body(), "$.ownerName")).isEqualTo("Alex Smith");
        assertThat(JsonPath.<String>read(retrieved.body(), "$.currency")).isEqualTo("AUD");
        assertThat(JsonPath.<Number>read(retrieved.body(), "$.balance").doubleValue()).isZero();
        assertThat(Instant.parse(JsonPath.read(retrieved.body(), "$.createdAt")))
                .isEqualTo(Instant.parse(JsonPath.read(created.body(), "$.createdAt")));
        assertThat(repository.findById(UUID.fromString(id))).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{", "[]",
            "{\"ownerName\":\"  \",\"currency\":\"AUD\"}",
            "{\"ownerName\":\"Alex\"}",
            "{\"ownerName\":\"Alex\",\"currency\":\"XYZ\"}",
            "{\"ownerName\":\"Alex\",\"currency\":0}",
            "{\"ownerName\":\"Alex\",\"currency\":\"0\"}",
            "{\"ownerName\":123,\"currency\":\"AUD\"}",
            "{\"ownerName\":12.5,\"currency\":\"AUD\"}",
            "{\"ownerName\":true,\"currency\":\"AUD\"}",
            "{\"ownerName\":\"Alex\",\"currency\":\"AUD\",\"balance\":100}"
    })
    void rejectsInvalidRequestsWithoutPersisting(String body) throws Exception {
        HttpResponse<String> response = post(body);
        assertProblem(response, 400);
        assertThat(repository.count()).isZero();
    }

    @Test
    void returnsFieldValidationErrors() throws Exception {
        HttpResponse<String> response = post("{\"ownerName\":\"\",\"currency\":null}");
        assertProblem(response, 400);
        assertThat(JsonPath.<String>read(response.body(), "$.errors.ownerName")).isNotBlank();
        assertThat(JsonPath.<String>read(response.body(), "$.errors.currency")).isNotBlank();
    }

    @Test
    void rejectsOversizedOwnerName() throws Exception {
        assertProblem(post("{\"ownerName\":\"" + "a".repeat(101) + "\",\"currency\":\"AUD\"}"), 400);
        assertThat(repository.count()).isZero();
    }

    @Test
    void reportsMissingAndMalformedIds() throws Exception {
        assertProblem(get("/api/accounts/" + UUID.randomUUID()), 404);
        assertProblem(get("/api/accounts/not-a-uuid"), 400);
    }

    @Test
    void databaseRejectsNegativeBalances() throws Exception {
        HttpResponse<String> created = post("{\"ownerName\":\"Alex\",\"currency\":\"AUD\"}");
        UUID id = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findById(id).orElseThrow().getBalance()).isEqualByComparingTo("0.00");
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/accounts"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(status);
    }
}
