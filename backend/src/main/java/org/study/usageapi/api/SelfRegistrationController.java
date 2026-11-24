package org.study.usageapi.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.study.usageapi.service.RegistrationService;
import org.study.usageapi.service.RequestIdFilter;

@RestController
@Profile("local & !prod")
@RequestMapping(value = "/api/v1/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "app")
public class SelfRegistrationController {

    private final RegistrationService registrations;
    public SelfRegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerUser", summary = "Create a local self-registration",
            description = "Local development only. Contact details are not verified; do not use real participant data.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registration created"),
            @ApiResponse(responseCode = "409", description = "A submitted contact is already registered",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "Request exceeds 256 KiB",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    headers = @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Retry-After", description = "Seconds until requests may resume"),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RegistrationDtos.SelfRegistrationResponse register(
            @Valid @RequestBody RegistrationDtos.SelfRegistrationRequest request) {
        return registrations.selfRegister(request, RequestIdFilter.currentRequestId());
    }
}
