package org.study.usageapi.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class RegistrationDtos {

    private RegistrationDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "RegistrationImportRequest")
    public record ImportRequest(
            @NotEmpty @Size(max = 500) List<@Valid Participant> registrations) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Participant(
            @NotBlank @Pattern(regexp = "P-[A-Z0-9]{6}") String registrationId,
            @NotNull @Min(0) @Max(120) Integer age,
            @NotBlank @Size(max = 64) String gender) {
    }

    public record ImportResponse(int imported) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SelfRegistrationRequest(
            @NotBlank @Size(max = 120) String userName,
            @NotBlank @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String mobileNumber,
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Min(0) @Max(120) Integer age,
            @NotBlank @Size(max = 64) String gender,
            @NotBlank @Size(max = 2000) String indoorActivity,
            @NotBlank @Size(max = 2000) String outdoorActivity,
            @NotNull @Min(3) @Max(12) Integer schemeMonths,
            @NotNull @Pattern(regexp = "student|working_professional|homemaker")
            @Schema(allowableValues = {"student", "working_professional", "homemaker"}) String profession) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SelfRegistrationResponse(String registrationId) {
    }

    public record StatusRequest(
            @NotBlank @Pattern(regexp = "not_registered|activated|deactivated")
            @Schema(allowableValues = {"not_registered", "activated", "deactivated"}) String status) {
    }
}
