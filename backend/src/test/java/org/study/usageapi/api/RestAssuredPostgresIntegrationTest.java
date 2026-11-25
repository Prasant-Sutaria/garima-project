package org.study.usageapi.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.SignedJWT;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.study.usageapi.crypto.EncryptedEnvelope;
import org.study.usageapi.crypto.KeyProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_JDBC_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestAssuredPostgresIntegrationTest {

    private static final String ADMIN_USERNAME = "integration-admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final Path KEYSTORE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "usage-api-http-test-" + UUID.randomUUID() + ".p12");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private KeyProvider keyProvider;

    private final List<String> importedRegistrations = new ArrayList<>();
    private String registrationId;
    private KeyPair deviceKey;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_JDBC_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_POSTGRES_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_POSTGRES_PASSWORD"));
        registry.add("backend.crypto.keystore-path", KEYSTORE_PATH::toString);
        registry.add("backend.crypto.keystore-password", () -> "integration-keystore-password");
        registry.add("backend.admin.username", () -> ADMIN_USERNAME);
        registry.add("backend.admin.password", () -> ADMIN_PASSWORD);
    }

    @BeforeEach
    void registerParticipantAndDevice() throws Exception {
        registrationId = importParticipant();
        deviceKey = generateKey();
        registerDevice(deviceKey).then().statusCode(201).body("status", equalTo("active"));
    }

    @AfterEach
    void removeTestData() {
        for (String id : importedRegistrations) {
            jdbc.update("DELETE FROM usage_snapshots WHERE registration_id = ?", id);
            jdbc.update("DELETE FROM auth_challenges WHERE registration_id = ?", id);
            jdbc.update("DELETE FROM devices WHERE registration_id = ?", id);
            jdbc.update("DELETE FROM registry.registrations WHERE registration_id = ?", id);
        }
        importedRegistrations.clear();
    }

    @AfterAll
    void removeKeystore() throws Exception {
        jdbc.update("""
                DELETE FROM registry.contact_index_key
                WHERE verifier = ? AND NOT EXISTS (SELECT 1 FROM registry.enrollment_details)
                """, keyProvider.contactFingerprint("system", "contact-index-key-verifier-v1"));
        Files.deleteIfExists(KEYSTORE_PATH);
    }

    @Test
    void healthAndJwksExposeTheKeyThatVerifiesIssuedTokens() throws Exception {
        request().get("/api/v1/health").then().statusCode(200).body("status", equalTo("UP"));
        Response jwks = request().get("/.well-known/jwks.json");
        jwks.then().statusCode(200).body("keys[0].alg", equalTo("ES256"))
                .body("keys[0].kty", equalTo("EC")).body("keys[0].crv", equalTo("P-256"))
                .body("keys[0].use", equalTo("sig")).body("keys[0].kid", notNullValue());
        SignedJWT token = SignedJWT.parse(authenticate(registrationId, deviceKey));
        ECKey signingKey = ECKey.parse(jwks.jsonPath().getMap("keys[0]"));
        assertEquals(signingKey.getKeyID(), token.getHeader().getKeyID());
        assertTrue(token.verify(new ECDSAVerifier(signingKey.toECPublicKey())));
        assertEquals("usage-api", token.getJWTClaimsSet().getIssuer());
        assertEquals(List.of("usage-ingest"), token.getJWTClaimsSet().getAudience());
        assertEquals(registrationId, token.getJWTClaimsSet().getSubject());
        assertEquals("usage:write", token.getJWTClaimsSet().getStringClaim("scope"));
    }

    @Test
    void selfRegistrationEncryptsDetailsAndCanAuthenticate() throws Exception {
        Map<String, Object> input = selfRegistration();
        Response response = request().contentType("application/json").body(input)
                .post("/api/v1/registrations");
        response.then().statusCode(201).body("registration_id", notNullValue());
        String id = response.jsonPath().getString("registration_id");
        importedRegistrations.add(id);
        assertTrue(id.matches("P-[A-Z0-9]{6}"));
        assertEquals(1, response.jsonPath().getMap("$").size());

        var row = jdbc.queryForMap("""
                SELECT r.age_enc, r.gender_enc, r.dek_wrapped AS fields_key, r.key_id AS fields_key_id,
                       d.details_enc, d.dek_wrapped AS details_key, d.key_id AS details_key_id
                FROM registry.registrations r JOIN registry.enrollment_details d USING (registration_id)
                WHERE r.registration_id = ?
                """, id);
        byte[] fieldsKey = (byte[]) row.get("fields_key");
        assertEquals("25", new String(keyProvider.decrypt(new EncryptedEnvelope(
                (byte[]) row.get("age_enc"), fieldsKey, (String) row.get("fields_key_id"))),
                StandardCharsets.UTF_8));
        assertEquals("test", new String(keyProvider.decrypt(new EncryptedEnvelope(
                (byte[]) row.get("gender_enc"), fieldsKey, (String) row.get("fields_key_id"))),
                StandardCharsets.UTF_8));
        byte[] plaintext = keyProvider.decrypt(new EncryptedEnvelope((byte[]) row.get("details_enc"),
                (byte[]) row.get("details_key"), (String) row.get("details_key_id")));
        var details = objectMapper.readTree(plaintext);
        assertEquals(input.get("user_name"), details.get("user_name").asText());
        assertEquals(input.get("mobile_number"), details.get("mobile_number").asText());
        assertEquals(input.get("email"), details.get("email").asText());
        assertEquals(input.get("indoor_activity"), details.get("indoor_activity").asText());
        assertEquals(input.get("outdoor_activity"), details.get("outdoor_activity").asText());
        assertEquals(input.get("scheme_months"), jdbc.queryForObject(
                "SELECT scheme_months FROM registry.enrollment_details WHERE registration_id = ?",
                Integer.class, id));
        assertEquals(input.get("profession"), jdbc.queryForObject(
                "SELECT profession FROM registry.enrollment_details WHERE registration_id = ?",
                String.class, id));
        KeyPair key = generateKey();
        registerDevice(id, key).then().statusCode(201);
        authenticate(id, key);
    }

    @Test
    void selfRegistrationRejectsDuplicateContactsAndInvalidInput() {
        Map<String, Object> input = selfRegistration();
        Response response = request().contentType("application/json").body(input)
                .post("/api/v1/registrations");
        response.then().statusCode(201);
        importedRegistrations.add(response.jsonPath().getString("registration_id"));
        Integer before = jdbc.queryForObject("SELECT count(*) FROM registry.registrations", Integer.class);

        Map<String, Object> duplicateEmail = new HashMap<>(input);
        duplicateEmail.put("email", ((String) input.get("email")).toUpperCase(java.util.Locale.ROOT));
        duplicateEmail.put("mobile_number", "+442071234567");
        request().contentType("application/json").body(duplicateEmail).post("/api/v1/registrations")
                .then().statusCode(409).body("code", equalTo("registration_exists"));
        Map<String, Object> duplicateMobile = new HashMap<>(input);
        duplicateMobile.put("email", UUID.randomUUID() + "@example.test");
        request().contentType("application/json").body(duplicateMobile).post("/api/v1/registrations")
                .then().statusCode(409).body("code", equalTo("registration_exists"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM registry.registrations", Integer.class));

        Map<String, Object> invalid = new HashMap<>(input);
        invalid.put("mobile_number", "1234");
        invalid.put("indoor_activity", "");
        request().contentType("application/json").body(invalid).post("/api/v1/registrations")
                .then().statusCode(422).body("code", equalTo("invalid_request"));

        byte[] verifier = keyProvider.contactFingerprint("system", "contact-index-key-verifier-v1");
        jdbc.update("UPDATE registry.contact_index_key SET verifier = ?", new byte[32]);
        try {
            request().contentType("application/json").body(selfRegistration()).post("/api/v1/registrations")
                    .then().statusCode(500).body("code", equalTo("internal_error"));
        } finally {
            jdbc.update("UPDATE registry.contact_index_key SET verifier = ?", verifier);
        }
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM registry.registrations", Integer.class));
    }

    @Test
    void selfRegistrationRejectsConcurrentDuplicateContacts() throws Exception {
        Map<String, Object> input = selfRegistration();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return request().contentType("application/json").body(input).post("/api/v1/registrations");
            });
            var second = executor.submit(() -> {
                start.await();
                return request().contentType("application/json").body(input).post("/api/v1/registrations");
            });
            start.countDown();
            List<Response> responses = List.of(first.get(), second.get());
            for (Response response : responses) {
                if (response.statusCode() == 201) {
                    importedRegistrations.add(response.jsonPath().getString("registration_id"));
                }
            }
            assertEquals(List.of(201, 409), responses.stream().map(Response::statusCode).sorted().toList());
        }
    }

    @Test
    void adminImportValidatesAndRejectsDuplicateBatchAtomically() {
        request().contentType("application/json").body(Map.of("registrations", List.of()))
                .post("/api/v1/admin/registrations").then().statusCode(401)
                .body("code", equalTo("unauthorized"));
        admin().contentType("application/json").body(Map.of("registrations", List.of()))
                .post("/api/v1/admin/registrations").then().statusCode(422)
                .body("code", equalTo("invalid_request"));

        String newId = newRegistrationId();
        admin().contentType("application/json")
                .body(Map.of("registrations", List.of(participant(newId), participant(registrationId))))
                .post("/api/v1/admin/registrations").then().statusCode(409)
                .body("code", equalTo("registration_exists"));
        admin().contentType("application/json").body(Map.of("registrations", List.of(participant(newId))))
                .post("/api/v1/admin/registrations").then().statusCode(201).body("imported", equalTo(1));
        importedRegistrations.add(newId);
    }

    @Test
    void adminStatusChangeValidatesAndRequiresAuthentication() throws Exception {
        request().contentType("application/json").body(Map.of("status", "deactivated"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(401);
        admin().contentType("application/json").body(Map.of("status", "invalid"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(422);
        admin().contentType("application/json").body(Map.of("status", "deactivated"))
                .patch("/api/v1/admin/registrations/{id}", newRegistrationId()).then().statusCode(404);
        admin().contentType("application/json").body(Map.of("status", "not_registered"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(409)
                .body("code", equalTo("invalid_status_transition"));

        String token = authenticate(registrationId, deviceKey);
        admin().contentType("application/json").body(Map.of("status", "deactivated"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(204)
                .body(equalTo(""));
        assertEquals("deactivated", registrationStatus(registrationId));
        request().auth().oauth2(token).get("/api/v1/usage/snapshots/{id}", UUID.randomUUID())
                .then().statusCode(403);
        UUID snapshotId = UUID.randomUUID();
        upload(token, snapshotId.toString(), snapshot(snapshotId)).then().statusCode(403);
        request().contentType("application/json").body(Map.of("registration_id", registrationId))
                .post("/api/v1/auth/challenge").then().statusCode(401);
        registerDevice(deviceKey).then().statusCode(403);

        admin().contentType("application/json").body(Map.of("status", "not_registered"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(409);
        admin().contentType("application/json").body(Map.of("status", "activated"))
                .patch("/api/v1/admin/registrations/{id}", registrationId).then().statusCode(204);
        assertEquals("activated", registrationStatus(registrationId));
        request().auth().oauth2(token).get("/api/v1/usage/snapshots/{id}", UUID.randomUUID())
                .then().statusCode(403);
        registerDevice(generateKey()).then().statusCode(201);
    }

    @Test
    void registrationStartsNotRegisteredAndActivatesOnFirstDevice() throws Exception {
        String newId = importParticipant();
        assertEquals("not_registered", registrationStatus(newId));
        request().contentType("application/json").body(Map.of("registration_id", newId))
                .post("/api/v1/auth/challenge").then().statusCode(401);
        admin().contentType("application/json").body(Map.of("status", "activated"))
                .patch("/api/v1/admin/registrations/{id}", newId).then().statusCode(409);

        KeyPair key = generateKey();
        registerDevice(newId, key).then().statusCode(201);
        assertEquals("activated", registrationStatus(newId));
        authenticate(newId, key);

        String unusedId = importParticipant();
        admin().contentType("application/json").body(Map.of("status", "deactivated"))
                .patch("/api/v1/admin/registrations/{id}", unusedId).then().statusCode(204);
        admin().contentType("application/json").body(Map.of("status", "activated"))
                .patch("/api/v1/admin/registrations/{id}", unusedId).then().statusCode(409);
        admin().contentType("application/json").body(Map.of("status", "not_registered"))
                .patch("/api/v1/admin/registrations/{id}", unusedId).then().statusCode(204);
        assertEquals("not_registered", registrationStatus(unusedId));
    }

    private String registrationStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM registry.registrations WHERE registration_id = ?",
                String.class, id);
    }

    @Test
    void deviceRegistrationReturnsSameIdentityAndRejectsInvalidKeys() throws Exception {
        String deviceId = registerDevice(deviceKey).then().statusCode(200).body("status", equalTo("active"))
                .extract().path("device_id");
        registerDevice(deviceKey).then().statusCode(200).body("device_id", equalTo(deviceId));
        registerDevice(generateKey()).then().statusCode(409).body("code", equalTo("device_exists"));
        registerDevice(newRegistrationId(), generateKey()).then().statusCode(403);

        String newId = importParticipant();
        request().contentType("application/json")
                .body(Map.of("registration_id", newId, "app_version", "1.0", "public_key",
                        Map.of("kty", "EC", "crv", "P-256", "x", "A".repeat(43), "y", "A".repeat(43))))
                .post("/api/v1/devices/register").then().statusCode(400).body("code", equalTo("invalid_key"));
        request().contentType("application/json")
                .body(Map.of("registration_id", "invalid", "app_version", "1.0"))
                .post("/api/v1/devices/register").then().statusCode(422);
    }

    @Test
    void uploadsRetriesAndChecksSnapshotOwnership() throws Exception {
        String token = authenticate(registrationId, deviceKey);
        UUID snapshotId = UUID.randomUUID();
        ObjectNode snapshot = snapshot(snapshotId);
        Response stored = upload(token, snapshotId.toString(), snapshot);
        stored.then().statusCode(201).body("snapshot_id", equalTo(snapshotId.toString()))
                .body("status", equalTo("stored")).body("received_at", notNullValue());
        String receivedAt = stored.jsonPath().getString("received_at");

        Response duplicate = upload(token, snapshotId.toString(), snapshot);
        duplicate.then().statusCode(200).body("status", equalTo("duplicate"));
        assertEquals(receivedAt, duplicate.jsonPath().getString("received_at"));

        request().auth().oauth2(token).get("/api/v1/usage/snapshots/{id}", snapshotId)
                .then().statusCode(200).body("status", equalTo("stored"))
                .body("received_at", equalTo(receivedAt));

        ObjectNode changed = snapshot.deepCopy();
        ((ObjectNode) changed.get("totals")).put("screen_on_seconds", 100);
        upload(token, snapshotId.toString(), changed).then().statusCode(409)
                .body("code", equalTo("snapshot_id_conflict"));

        UUID anotherId = UUID.randomUUID();
        ObjectNode another = snapshot.deepCopy().put("snapshot_id", anotherId.toString());
        upload(token, anotherId.toString(), another).then().statusCode(409)
                .body("code", equalTo("scheduled_exists"));
        another.put("trigger", "manual");
        upload(token, anotherId.toString(), another).then().statusCode(201)
                .body("status", equalTo("stored"));

        String otherRegistration = importParticipant();
        KeyPair otherKey = generateKey();
        registerDevice(otherRegistration, otherKey).then().statusCode(201);
        String otherToken = authenticate(otherRegistration, otherKey);
        request().auth().oauth2(otherToken).get("/api/v1/usage/snapshots/{id}", snapshotId)
                .then().statusCode(404).body("code", equalTo("not_found"));
        ObjectNode otherUserPayload = snapshot.deepCopy().put("snapshot_id", UUID.randomUUID().toString());
        upload(otherToken, otherUserPayload.get("snapshot_id").asText(), otherUserPayload)
                .then().statusCode(403);
        request().get("/api/v1/usage/snapshots/{id}", snapshotId).then().statusCode(401);
        request().auth().oauth2(token).get("/api/v1/usage/snapshots/{id}", "not-a-uuid")
                .then().statusCode(400);
    }

    @Test
    void rejectsInvalidProofAndNonceReplay() throws Exception {
        Response challenge = challenge(registrationId);
        assertEquals(32, Base64.getUrlDecoder().decode(challenge.jsonPath().getString("nonce")).length);
        assertEquals(Duration.ofSeconds(60), Duration.between(
                Instant.parse(challenge.jsonPath().getString("server_time")),
                Instant.parse(challenge.jsonPath().getString("expires_at"))));
        Map<String, String> wrongProof = proof(registrationId, challenge, generateKey());
        request().contentType("application/json").body(wrongProof)
                .post("/api/v1/auth/token").then().statusCode(401).body("code", equalTo("unauthorized"));
        request().contentType("application/json").body(proof(registrationId, challenge, deviceKey))
                .post("/api/v1/auth/token").then().statusCode(401);

        Response validChallenge = challenge(registrationId);
        Map<String, String> validProof = proof(registrationId, validChallenge, deviceKey);
        request().contentType("application/json").body(validProof)
                .post("/api/v1/auth/token").then().statusCode(200)
                .body("token_type", equalTo("Bearer")).body("expires_in", equalTo(600));
        request().contentType("application/json").body(validProof)
                .post("/api/v1/auth/token").then().statusCode(401);
    }

    @Test
    void challengeAndTokenRejectInvalidRequestsAndOldTimestamps() throws Exception {
        String noDeviceId = importParticipant();
        request().contentType("application/json").body(Map.of("registration_id", noDeviceId))
                .post("/api/v1/auth/challenge").then().statusCode(401);
        request().contentType("application/json").body(Map.of("registration_id", "invalid"))
                .post("/api/v1/auth/challenge").then().statusCode(422);
        Response challenge = challenge(registrationId);
        Map<String, String> validProof = proof(registrationId, challenge, deviceKey);
        Map<String, String> oldProof = new HashMap<>(validProof);
        oldProof.put("timestamp", "2000-01-01T00:00:00Z");
        request().contentType("application/json").body(oldProof)
                .post("/api/v1/auth/token").then().statusCode(401);
        request().contentType("application/json").body(Map.of("registration_id", registrationId))
                .post("/api/v1/auth/token").then().statusCode(422);
        request().contentType("application/json").body(validProof)
                .post("/api/v1/auth/token").then().statusCode(200);
    }

    @Test
    void rejectsUnauthorizedInvalidAndOversizedRequests() throws Exception {
        request().contentType("application/json").body("{\"registrations\":[]}")
                .post("/api/v1/admin/registrations").then().statusCode(401);
        request().contentType("application/json").body("{}")
                .post("/api/v1/usage/snapshots").then().statusCode(401);

        String token = authenticate(registrationId, deviceKey);
        UUID id = UUID.randomUUID();
        ObjectNode snapshot = snapshot(id);
        request().auth().oauth2(token).contentType("application/json").body(snapshot.toString())
                .post("/api/v1/usage/snapshots").then().statusCode(400).body("code", equalTo("invalid_request"));
        request().auth().oauth2("invalid-token").header("Idempotency-Key", id.toString())
                .contentType("application/json").body(snapshot.toString())
                .post("/api/v1/usage/snapshots").then().statusCode(401);
        Response mismatch = upload(token, UUID.randomUUID().toString(), snapshot);
        mismatch.then().statusCode(400)
                .body("code", equalTo("idempotency_key_mismatch"));
        assertEquals(mismatch.header("X-Request-Id"), mismatch.jsonPath().getString("request_id"));
        ((ObjectNode) snapshot.get("totals")).put("active_app_count", 2);
        upload(token, id.toString(), snapshot).then().statusCode(422)
                .body("code", equalTo("invalid_payload"));
        request().auth().oauth2(token).header("Idempotency-Key", id.toString())
                .contentType("application/json").body("x".repeat(262145))
                .post("/api/v1/usage/snapshots").then().statusCode(413)
                .body("code", equalTo("payload_too_large"));
        request().auth().oauth2(token).header("Idempotency-Key", id.toString())
                .contentType("application/json").body("x".repeat(262144))
                .post("/api/v1/usage/snapshots").then().statusCode(422);
    }

    @Test
    void enforcesChallengeRateLimit() {
        for (int i = 0; i < 10; i++) {
            challenge(registrationId);
        }
        request().contentType("application/json").body(Map.of("registration_id", registrationId))
                .post("/api/v1/auth/challenge").then().statusCode(429)
                .header("Retry-After", "60").body("code", equalTo("rate_limited"));
    }

    @Test
    void enforcesTokenRateLimit() throws Exception {
        Map<String, String> tokenProof = proof(registrationId, challenge(registrationId), deviceKey);
        request().contentType("application/json").body(tokenProof)
                .post("/api/v1/auth/token").then().statusCode(200);
        for (int i = 1; i < 10; i++) {
            request().contentType("application/json").body(tokenProof)
                    .post("/api/v1/auth/token").then().statusCode(401);
        }
        request().contentType("application/json").body(tokenProof)
                .post("/api/v1/auth/token").then().statusCode(429)
                .header("Retry-After", "60").body("code", equalTo("rate_limited"));
    }

    @Test
    void enforcesSnapshotUploadRateLimit() throws Exception {
        String token = authenticate(registrationId, deviceKey);
        UUID id = UUID.randomUUID();
        ObjectNode snapshot = snapshot(id);
        upload(token, id.toString(), snapshot).then().statusCode(201);
        for (int i = 1; i < 60; i++) {
            upload(token, id.toString(), snapshot).then().statusCode(200);
        }
        upload(token, id.toString(), snapshot).then().statusCode(429)
                .header("Retry-After", "60").body("code", equalTo("rate_limited"));
    }

    private RequestSpecification request() {
        return given().baseUri("http://127.0.0.1").port(port);
    }

    private RequestSpecification admin() {
        return request().auth().preemptive().basic(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    private String importParticipant() {
        String id = newRegistrationId();
        admin().contentType("application/json")
                .body(Map.of("registrations", List.of(participant(id))))
                .post("/api/v1/admin/registrations").then().statusCode(201).body("imported", equalTo(1));
        importedRegistrations.add(id);
        return id;
    }

    private String newRegistrationId() {
        return "P-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
    }

    private Map<String, Object> participant(String id) {
        return Map.of("registration_id", id, "age", 25, "gender", "test");
    }

    private Map<String, Object> selfRegistration() {
        String digits = String.format(java.util.Locale.ROOT, "%010d",
                new java.security.SecureRandom().nextInt(1_000_000_000));
        return Map.of("user_name", "Example Person", "mobile_number", "+1555" + digits,
                "email", UUID.randomUUID() + "@example.test", "age", 25, "gender", "test",
                "indoor_activity", "Reading", "outdoor_activity", "Walking", "scheme_months", 3,
                "profession", "working_professional");
    }

    private Response registerDevice(KeyPair key) {
        return registerDevice(registrationId, key);
    }

    private Response registerDevice(String id, KeyPair key) {
        ECPublicKey publicKey = (ECPublicKey) key.getPublic();
        Map<String, String> jwk = Map.of("kty", "EC", "crv", "P-256",
                "x", coordinate(publicKey.getW().getAffineX()),
                "y", coordinate(publicKey.getW().getAffineY()));
        return request().contentType("application/json")
                .body(Map.of("registration_id", id, "public_key", jwk, "app_version", "1.0"))
                .post("/api/v1/devices/register");
    }

    private Response challenge(String id) {
        Response response = request().contentType("application/json").body(Map.of("registration_id", id))
                .post("/api/v1/auth/challenge");
        response.then().statusCode(200).body("nonce", notNullValue()).body("server_time", notNullValue());
        return response;
    }

    private String authenticate(String id, KeyPair key) throws Exception {
        Response response = request().contentType("application/json").body(proof(id, challenge(id), key))
                .post("/api/v1/auth/token");
        response.then().statusCode(200).body("access_token", notNullValue());
        return response.jsonPath().getString("access_token");
    }

    private Map<String, String> proof(String id, Response challenge, KeyPair key) throws Exception {
        String nonce = challenge.jsonPath().getString("nonce");
        String timestamp = challenge.jsonPath().getString("server_time");
        String message = "usage-auth-v1\n" + nonce + "\n" + id + "\n" + timestamp;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Map.of("registration_id", id, "nonce", nonce, "timestamp", timestamp,
                "signature", Base64.getEncoder().encodeToString(signer.sign()));
    }

    private ObjectNode snapshot(UUID id) throws Exception {
        ObjectNode snapshot = (ObjectNode) objectMapper.readTree("""
                {
                  "schema_version": "1.0", "trigger": "scheduled", "date": "2026-01-15",
                  "app_version": "1.0", "timezone": "Asia/Kolkata", "utc_offset_minutes": 330,
                  "window": {"start": "2026-01-15T00:00:00+05:30", "end": "2026-01-15T23:55:00+05:30"},
                  "collected_at": "2026-01-15T23:55:00+05:30",
                  "totals": {"screen_on_seconds": 3600, "unlock_count": 12, "notification_count": 8,
                    "notification_source": "measured", "notification_count_synthetic": false, "active_app_count": 1},
                  "apps": [{"package_name": "example.app", "foreground_seconds": 1800, "launch_count": 5}],
                  "quality": {"event_count": 42, "open_sessions_closed": 0, "unmatched_events": 0}
                }
                """);
        snapshot.put("snapshot_id", id.toString());
        snapshot.put("user_id", registrationId);
        return snapshot;
    }

    private Response upload(String token, String idempotencyKey, ObjectNode snapshot) {
        return request().auth().oauth2(token).header("Idempotency-Key", idempotencyKey)
                .contentType("application/json").body(snapshot.toString())
                .post("/api/v1/usage/snapshots");
    }

    private KeyPair generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String coordinate(BigInteger value) {
        byte[] source = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(source.length, fixed.length);
        System.arraycopy(source, source.length - length, fixed, fixed.length - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }
}
