package org.study.usageapi.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.persistence.RegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_JDBC_URL", matches = ".+")
@SpringBootTest
@ActiveProfiles("local")
class PostgresAuthenticationIntegrationTest {

    private static final Path KEYSTORE_PATH = Path.of(System.getProperty("java.io.tmpdir"),
            "usage-api-postgres-test-" + UUID.randomUUID() + ".p12");

    @Autowired
    private RegistrationRepository registrations;
    @Autowired
    private DeviceService devices;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private JdbcTemplate jdbc;

    private String registrationId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_JDBC_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_POSTGRES_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_POSTGRES_PASSWORD"));
        registry.add("backend.crypto.keystore-path", KEYSTORE_PATH::toString);
        registry.add("backend.crypto.keystore-password", () -> "postgres-test-keystore-password");
        registry.add("backend.admin.username", () -> "postgres-test-admin");
        registry.add("backend.admin.password", () -> "postgres-test-admin-password");
    }

    @AfterEach
    void removeTestRegistration() {
        if (registrationId != null) {
            jdbc.update("DELETE FROM auth_challenges WHERE registration_id = ?", registrationId);
            jdbc.update("DELETE FROM devices WHERE registration_id = ?", registrationId);
            jdbc.update("DELETE FROM registry.registrations WHERE registration_id = ?", registrationId);
        }
    }

    @AfterAll
    static void removeKeystore() throws Exception {
        Files.deleteIfExists(KEYSTORE_PATH);
    }

    @Test
    void storesTimestampsAndConsumesNonceEvenWhenSignatureFails() throws Exception {
        registrationId = "P-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        registrations.insert(registrationId, new byte[]{1}, new byte[]{2}, new byte[]{3}, "test");
        assertEquals("not_registered", registrations.findStatus(registrationId).orElseThrow());

        KeyPair deviceKey = generateKey();
        ECPublicKey publicKey = (ECPublicKey) deviceKey.getPublic();
        var jwk = new DeviceDtos.PublicKeyJwk("EC", "P-256",
                coordinate(publicKey.getW().getAffineX()),
                coordinate(publicKey.getW().getAffineY()));
        assertEquals(401, assertThrows(ApiException.class,
                () -> authentication.issueChallenge(registrationId, "127.0.0.1")).status().value());
        devices.register(new DeviceDtos.RegisterRequest(registrationId, jwk, "1.0.0"));
        assertEquals("activated", registrations.findStatus(registrationId).orElseThrow());

        var challenge = authentication.issueChallenge(registrationId, "127.0.0.1");
        var badProof = proof(challenge, generateKey());
        var badSignature = assertThrows(ApiException.class,
                () -> authentication.issueToken(badProof, "127.0.0.1"));
        assertEquals(401, badSignature.status().value());
        var replay = assertThrows(ApiException.class,
                () -> authentication.issueToken(proof(challenge, deviceKey), "127.0.0.1"));
        assertEquals(401, replay.status().value());

        var validChallenge = authentication.issueChallenge(registrationId, "127.0.0.1");
        var validProof = proof(validChallenge, deviceKey);
        assertEquals("Bearer", authentication.issueToken(validProof, "127.0.0.1").tokenType());
        var replayedValidProof = assertThrows(ApiException.class,
                () -> authentication.issueToken(validProof, "127.0.0.1"));
        assertEquals(401, replayedValidProof.status().value());
    }

    private DeviceDtos.TokenRequest proof(DeviceDtos.ChallengeResponse challenge, KeyPair keyPair)
            throws Exception {
        String timestamp = Instant.now().toString();
        var unsigned = new DeviceDtos.TokenRequest(registrationId, challenge.nonce(), timestamp, "");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(AuthenticationService.signedMessage(unsigned).getBytes(StandardCharsets.UTF_8));
        return new DeviceDtos.TokenRequest(registrationId, challenge.nonce(), timestamp,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private KeyPair generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private String coordinate(java.math.BigInteger value) {
        byte[] source = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(source.length, fixed.length);
        System.arraycopy(source, source.length - length, fixed, fixed.length - length, length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }
}
