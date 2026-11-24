package org.study.usageapi.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.study.usageapi.service.RegistrationService;
import org.study.usageapi.service.RequestIdFilter;

@RestController
@RequestMapping(value = "/api/v1/admin/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "admin")
@SecurityRequirement(name = "adminBasic")
public class AdminRegistrationController {

    private final RegistrationService registrationService;

    public AdminRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "adminImportRegistrations",
            summary = "Import encrypted study registration records",
            description = "Admin-only. Age and gender are encrypted immediately and are never logged or exported.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Records imported"),
            @ApiResponse(responseCode = "401", description = "Admin authentication failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "One or more IDs already exist",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public org.springframework.http.ResponseEntity<RegistrationDtos.ImportResponse> importRegistrations(
            @Valid @RequestBody RegistrationDtos.ImportRequest request, Authentication authentication) {
        int imported = registrationService.importRegistrations(
                request, authentication.getName(), RequestIdFilter.currentRequestId());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegistrationDtos.ImportResponse(imported));
    }

    @PatchMapping(value = "/{registration_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "adminUpdateRegistration",
            summary = "Update registration status",
            description = "Changing status to withdrawn immediately prevents further authentication and uploads.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Status changed"),
            @ApiResponse(responseCode = "401", description = "Admin authentication failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Registration not found",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public org.springframework.http.ResponseEntity<Void> updateStatus(
            @PathVariable("registration_id") String registrationId,
            @Valid @RequestBody RegistrationDtos.StatusRequest request,
            Authentication authentication) {
        registrationService.updateStatus(registrationId, request.status(),
                authentication.getName(), RequestIdFilter.currentRequestId());
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
