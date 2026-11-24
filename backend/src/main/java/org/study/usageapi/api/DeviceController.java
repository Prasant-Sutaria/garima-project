package org.study.usageapi.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.crypto.SigningKeyMaterial;
import org.study.usageapi.service.AuthenticationService;
import org.study.usageapi.service.DeviceService;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "app")
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class DeviceController {

    private final DeviceService deviceService;
    private final AuthenticationService authenticationService;
    private final KeyProvider keyProvider;

    public DeviceController(DeviceService deviceService, AuthenticationService authenticationService,
                            KeyProvider keyProvider) {
        this.deviceService = deviceService;
        this.authenticationService = authenticationService;
        this.keyProvider = keyProvider;
    }

    @PostMapping(value = "/api/v1/devices/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "registerDevice", summary = "Register a P-256 device public key")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The same active key was already registered"),
            @ApiResponse(responseCode = "201", description = "Device registered"),
            @ApiResponse(responseCode = "400", description = "Invalid public key",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Registration is unknown or inactive",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "A different active device already exists",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Invalid key or request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DeviceDtos.DeviceResponse> register(
            @Valid @RequestBody DeviceDtos.RegisterRequest request) {
        var result = deviceService.register(request);
        var response = new DeviceDtos.DeviceResponse(result.deviceId().toString(), "active");
        return ResponseEntity.status(result.alreadyRegistered() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/api/v1/auth/challenge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "issueChallenge", summary = "Issue a single-use device authentication nonce")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nonce and server time"),
            @ApiResponse(responseCode = "401", description = "Registration or device is not active",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    headers = @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Retry-After", description = "Seconds until requests may resume"),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public DeviceDtos.ChallengeResponse challenge(
            @Valid @RequestBody DeviceDtos.ChallengeRequest request, HttpServletRequest servletRequest) {
        return authenticationService.issueChallenge(request.registrationId(), servletRequest.getRemoteAddr());
    }

    @PostMapping(value = "/api/v1/auth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "issueToken", summary = "Verify signed nonce and issue a short-lived access token",
            description = "The signature covers usage-auth-v1, nonce, registration_id, and timestamp separated by LF.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Access token issued"),
            @ApiResponse(responseCode = "401", description = "Invalid or replayed authentication proof",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    headers = @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Retry-After", description = "Seconds until requests may resume"),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public DeviceDtos.TokenResponse token(@Valid @RequestBody DeviceDtos.TokenRequest request,
                                          HttpServletRequest servletRequest) {
        return authenticationService.issueToken(request, servletRequest.getRemoteAddr());
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(operationId = "getJwks", summary = "Publish active token verification keys")
    @ApiResponse(responseCode = "200", description = "Public EC signing keys")
    public Map<String, List<Map<String, String>>> jwks() {
        return Map.of("keys", keyProvider.publishedSigningKeys().stream().map(this::toJwk).toList());
    }

    private Map<String, String> toJwk(SigningKeyMaterial material) {
        var publicKey = (ECPublicKey) material.publicKey();
        return Map.of(
                "kty", "EC",
                "crv", "P-256",
                "kid", material.keyId(),
                "alg", "ES256",
                "use", "sig",
                "x", Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate(publicKey.getW().getAffineX())),
                "y", Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate(publicKey.getW().getAffineY())));
    }

    private byte[] coordinate(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, fixed.length);
        System.arraycopy(raw, raw.length - length, fixed, fixed.length - length, length);
        return fixed;
    }
}
