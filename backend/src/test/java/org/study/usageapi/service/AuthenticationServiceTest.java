package org.study.usageapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.crypto.SigningKeyMaterial;
import org.study.usageapi.persistence.ChallengeRepository;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {

    private static final String ID = "P-7K3Q9X";
    private static final Instant NOW = Instant.parse("2026-11-20T18:00:00Z");

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final ChallengeRepository challenges = mock(ChallengeRepository.class);
    private final KeyProvider keyProvider = mock(KeyProvider.class);
    private final DeviceKeyCodec keyCodec = mock(DeviceKeyCodec.class);
    private final UUID deviceId = UUID.randomUUID();
    private AuthenticationService service;
    private KeyPair deviceKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair serverKey = generateKey();
        deviceKey = generateKey();
        when(keyProvider.activeSigningKey()).thenReturn(
                new SigningKeyMaterial("k1", serverKey.getPrivate(), serverKey.getPublic()));
        when(keyProvider.signingPublicKey("k1")).thenReturn(serverKey.getPublic());
        when(keyCodec.decodeJson("{}")).thenReturn(deviceKey.getPublic());
        var device = new DeviceRepository.Device(deviceId, ID, "{}", "1.0.0", "active");
        when(devices.findActiveByRegistration(ID)).thenReturn(Optional.of(device));
        when(devices.findActive(deviceId, ID)).thenReturn(Optional.of(device));
        when(challenges.consume(any(byte[].class), anyString(), any(Instant.class))).thenReturn(true);
        service = new AuthenticationService(registrations, devices, challenges, keyProvider, keyCodec,
                mock(RateLimiter.class), Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_registered", "deactivated"})
    void loginIsRejectedWithGenericErrorUnlessActivated(String status) throws Exception {
        when(registrations.findStatus(ID)).thenReturn(Optional.of(status));

        ApiException challenge = assertThrows(ApiException.class, () -> service.issueChallenge(ID, "1.2.3.4"));
        ApiException token = assertThrows(ApiException.class, () -> service.issueToken(proof(), "1.2.3.4"));

        for (ApiException exception : new ApiException[]{challenge, token}) {
            assertEquals(401, exception.status().value());
            assertEquals("unauthorized", exception.code());
        }
        verify(challenges, never()).insert(any(byte[].class), anyString(), any(Instant.class), any(Instant.class));
    }

    @Test
    void accessTokenIsRejectedOnceTheRegistrationIsDeactivated() throws Exception {
        when(registrations.findStatus(ID)).thenReturn(Optional.of("activated"));
        String token = service.issueToken(proof(), "1.2.3.4").accessToken();
        assertEquals(ID, service.validateAccessToken(token).registrationId());

        when(registrations.findStatus(ID)).thenReturn(Optional.of("deactivated"));
        ApiException exception = assertThrows(ApiException.class, () -> service.validateAccessToken(token));

        assertEquals(403, exception.status().value());
        assertEquals("registration_deactivated", exception.code());
    }

    private DeviceDtos.TokenRequest proof() throws Exception {
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var unsigned = new DeviceDtos.TokenRequest(ID, nonce, NOW.toString(), "");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(deviceKey.getPrivate());
        signer.update(AuthenticationService.signedMessage(unsigned).getBytes(StandardCharsets.UTF_8));
        return new DeviceDtos.TokenRequest(ID, nonce, NOW.toString(),
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static KeyPair generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
