package org.study.usageapi.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.study.usageapi.crypto.KeyProvider;

import java.util.Map;

@RestController
@Tag(name = "health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final KeyProvider keyProvider;

    public HealthController(JdbcTemplate jdbc, KeyProvider keyProvider) {
        this.jdbc = jdbc;
        this.keyProvider = keyProvider;
    }

    @GetMapping(value = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "health", summary = "Check API readiness")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Database and key provider are available"),
            @ApiResponse(responseCode = "503", description = "Service unavailable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Map<String, String> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            if (!keyProvider.isAvailable()) {
                throw new IllegalStateException("Key provider is not available");
            }
            return Map.of("status", "UP");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");
        }
    }
}
