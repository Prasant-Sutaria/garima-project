package org.study.usageapi.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class UsageDtos {

    private UsageDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(name = "UsageSnapshot")
    public record Snapshot(
            @NotBlank @Pattern(regexp = "1\\.0") String schemaVersion,
            @NotNull UUID snapshotId,
            @NotNull Trigger trigger,
            @NotNull LocalDate date,
            @NotBlank @Pattern(regexp = "P-[A-Z0-9]{6}") String userId,
            @NotBlank @Size(max = 32) String appVersion,
            @NotBlank @Size(max = 64) String timezone,
            @NotNull @Min(-840) @Max(840) Integer utcOffsetMinutes,
            @NotNull @Valid UsageWindow window,
            @NotNull OffsetDateTime collectedAt,
            @NotNull @Valid Totals totals,
            @NotNull @Size(max = 500) List<@Valid AppUsage> apps,
            @NotNull @Valid Quality quality) {
    }

    public enum Trigger {
        scheduled,
        manual
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UsageWindow(@NotNull OffsetDateTime start, @NotNull OffsetDateTime end) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Totals(
            @NotNull @Min(0) Integer screenOnSeconds,
            @Min(0) Integer unlockCount,
            @Min(0) Integer notificationCount,
            @NotNull NotificationSource notificationSource,
            @NotNull Boolean notificationCountSynthetic,
            @NotNull @Min(0) Integer activeAppCount) {
    }

    public enum NotificationSource {
        measured,
        synthetic,
        unavailable
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppUsage(
            @NotBlank @Size(max = 255) String packageName,
            @NotNull @Min(0) Integer foregroundSeconds,
            @NotNull @Min(0) Integer launchCount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Quality(
            @NotNull @Min(0) Integer eventCount,
            @NotNull @Min(0) Integer openSessionsClosed,
            @NotNull @Min(0) Integer unmatchedEvents) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UploadResponse(UUID snapshotId, String status, OffsetDateTime receivedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotStatus(UUID snapshotId, String status, OffsetDateTime receivedAt) {
    }
}
