package org.study.usageapi.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "DeviceRegistrationRequest")
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "P-[A-Z0-9]{6}") String registrationId,
            @NotNull @Valid PublicKeyJwk publicKey,
            @NotBlank @Size(max = 32) String appVersion) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PublicKeyJwk(
            @NotBlank @Pattern(regexp = "EC") String kty,
            @NotBlank @Pattern(regexp = "P-256") String crv,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String x,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String y) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceResponse(String deviceId, String status) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChallengeRequest(
            @NotBlank @Pattern(regexp = "P-[A-Z0-9]{6}") String registrationId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChallengeResponse(String nonce, String expiresAt, String serverTime) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenRequest(
            @NotBlank @Pattern(regexp = "P-[A-Z0-9]{6}") String registrationId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String nonce,
            @NotBlank String timestamp,
            @NotBlank @Size(max = 160) String signature) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenResponse(String accessToken, String tokenType, int expiresIn) {
    }
}
