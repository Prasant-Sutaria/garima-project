package org.study.usageapi.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.crypto.SigningKeyMaterial;
import org.study.usageapi.persistence.ChallengeRepository;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class AuthenticationService {

    private static final String ISSUER = "usage-api";
    private static final String AUDIENCE = "usage-ingest";
    private static final String SCOPE = "usage:write";
    private static final Duration CHALLENGE_TTL = Duration.ofSeconds(60);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofSeconds(60);

    private final RegistrationRepository registrations;
    private final DeviceRepository devices;
    private final ChallengeRepository challenges;
    private final KeyProvider keyProvider;
    private final DeviceKeyCodec keyCodec;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final java.security.SecureRandom secureRandom;

    public AuthenticationService(RegistrationRepository registrations, DeviceRepository devices,
                                 ChallengeRepository challenges, KeyProvider keyProvider,
                                 DeviceKeyCodec keyCodec, RateLimiter rateLimiter, Clock clock,
                                 java.security.SecureRandom secureRandom) {
        this.registrations = registrations;
        this.devices = devices;
        this.challenges = challenges;
        this.keyProvider = keyProvider;
        this.keyCodec = keyCodec;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public DeviceDtos.ChallengeResponse issueChallenge(String registrationId, String sourceIp) {
        rateLimiter.check("challenge:registration:" + registrationId, 10);
        rateLimiter.check("challenge:ip:" + sourceIp, 100);
        if (!registrations.findStatus(registrationId).filter("active"::equals).isPresent()
                || devices.findActiveByRegistration(registrationId).isEmpty()) {
            throw unauthorized();
        }
        Instant now = clock.instant();
        byte[] nonce = new byte[32];
        secureRandom.nextBytes(nonce);
        String encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        challenges.insert(sha256(nonce), registrationId, now, now.plus(CHALLENGE_TTL));
        return new DeviceDtos.ChallengeResponse(encodedNonce, now.plus(CHALLENGE_TTL).toString(), now.toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public DeviceDtos.TokenResponse issueToken(DeviceDtos.TokenRequest request, String sourceIp) {
        rateLimiter.check("token:registration:" + request.registrationId(), 10);
        rateLimiter.check("token:ip:" + sourceIp, 100);

        Instant now = clock.instant();
        Instant signedAt;
        byte[] nonce;
        try {
            signedAt = Instant.parse(request.timestamp());
            nonce = Base64.getUrlDecoder().decode(request.nonce());
            if (nonce.length != 32 || Duration.between(signedAt, now).abs().compareTo(ALLOWED_CLOCK_SKEW) > 0) {
                throw unauthorized();
            }
        } catch (RuntimeException exception) {
            throw unauthorized();
        }

        var status = registrations.findStatus(request.registrationId());
        var device = devices.findActiveByRegistration(request.registrationId());
        if (!status.filter("active"::equals).isPresent() || device.isEmpty()) {
            throw unauthorized();
        }
        if (!challenges.consume(sha256(nonce), request.registrationId(), now)) {
            throw unauthorized();
        }

        boolean signatureValid = verifyDeviceSignature(request, device.get().publicKeyJson());
        if (!signatureValid) {
            throw unauthorized();
        }

        return createToken(request.registrationId(), device.get().id(), now);
    }

    private boolean verifyDeviceSignature(DeviceDtos.TokenRequest request, String publicKeyJson) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(request.signature());
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(keyCodec.decodeJson(publicKeyJson));
            verifier.update(signedMessage(request).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception exception) {
            return false;
        }
    }

    public static String signedMessage(DeviceDtos.TokenRequest request) {
        return "usage-auth-v1\n" + request.nonce() + "\n"
                + request.registrationId() + "\n" + request.timestamp();
    }

    private DeviceDtos.TokenResponse createToken(String registrationId, UUID deviceId, Instant now) {
        SigningKeyMaterial signingKey = keyProvider.activeSigningKey();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(registrationId)
                    .audience(AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(TOKEN_TTL)))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("did", deviceId.toString())
                    .claim("scope", SCOPE)
                    .build();
            SignedJWT token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.keyId()).build(), claims);
            token.sign(new ECDSASigner((java.security.interfaces.ECPrivateKey) signingKey.privateKey()));
            return new DeviceDtos.TokenResponse(token.serialize(), "Bearer", (int) TOKEN_TTL.toSeconds());
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to issue access token", exception);
        }
    }

    public ParticipantPrincipal validateAccessToken(String serializedToken) {
        try {
            SignedJWT token = SignedJWT.parse(serializedToken);
            var claims = token.getJWTClaimsSet();
            var verificationKey = keyProvider.signingPublicKey(token.getHeader().getKeyID());
            if (verificationKey == null
                    || !JWSAlgorithm.ES256.equals(token.getHeader().getAlgorithm())
                    || !token.verify(new ECDSAVerifier((java.security.interfaces.ECPublicKey) verificationKey))
                    || !ISSUER.equals(claims.getIssuer())
                    || !claims.getAudience().contains(AUDIENCE)
                    || !SCOPE.equals(claims.getStringClaim("scope"))
                    || claims.getJWTID() == null || claims.getJWTID().isBlank()
                    || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(clock.instant())
                    || claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().isAfter(clock.instant().plus(ALLOWED_CLOCK_SKEW))
                    || claims.getExpirationTime().toInstant().isAfter(
                    claims.getIssueTime().toInstant().plus(TOKEN_TTL).plus(ALLOWED_CLOCK_SKEW))) {
                throw unauthorized();
            }
            UUID deviceId = UUID.fromString(claims.getStringClaim("did"));
            String registrationId = claims.getSubject();
            if (devices.findActive(deviceId, registrationId).isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This device is not active.");
            }
            devices.touch(deviceId);
            return new ParticipantPrincipal(registrationId, deviceId);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized();
        }
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication failed.");
    }
}
