package org.study.usageapi.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.study.usageapi.service.ParticipantPrincipal;
import org.study.usageapi.service.RequestSizeFilter;
import org.study.usageapi.service.UsageSnapshotService;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/usage/snapshots", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "app")
public class UsageController {

    private final UsageSnapshotService snapshotService;

    public UsageController(UsageSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "uploadSnapshot", summary = "Store an idempotent usage snapshot",
            description = "Maximum request size is 256 KiB. Retries with the same snapshot ID and canonical body are safe.",
            security = @SecurityRequirement(name = "deviceBearer"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Same snapshot was already stored"),
            @ApiResponse(responseCode = "201", description = "New snapshot stored"),
            @ApiResponse(responseCode = "400", description = "Invalid idempotency header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Invalid or expired token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Device or registration is inactive",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Snapshot ID or scheduled date conflict",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Invalid payload",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    headers = @Header(name = "Retry-After", description = "Seconds until requests may resume"),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Internal error",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            description = "Must exactly equal snapshot_id.")
    public org.springframework.http.ResponseEntity<UsageDtos.UploadResponse> upload(
            @Valid @RequestBody UsageDtos.Snapshot snapshot,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal ParticipantPrincipal participant,
            HttpServletRequest servletRequest) {
        byte[] rawPayload = (byte[]) servletRequest.getAttribute(RequestSizeFilter.REQUEST_BODY_ATTRIBUTE);
        if (rawPayload == null) {
            throw new IllegalStateException("The bounded request body is unavailable");
        }
        UsageDtos.UploadResponse response = snapshotService.upload(
                snapshot, idempotencyKey, participant, rawPayload);
        HttpStatus status = "duplicate".equals(response.status()) ? HttpStatus.OK : HttpStatus.CREATED;
        return org.springframework.http.ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{snapshot_id}")
    @Operation(operationId = "getSnapshotStatus", summary = "Read the status of an owned snapshot",
            security = @SecurityRequirement(name = "deviceBearer"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Snapshot status"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Device or registration is inactive",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Snapshot not found for this participant",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public UsageDtos.SnapshotStatus status(@PathVariable("snapshot_id") UUID snapshotId,
                                           @AuthenticationPrincipal ParticipantPrincipal participant) {
        return snapshotService.getStatus(snapshotId, participant);
    }
}
