package org.study.usageapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.UsageDtos;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;
import org.study.usageapi.persistence.SnapshotRepository;

import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.UUID;

@Service
public class UsageSnapshotService {

    private final RegistrationRepository registrations;
    private final DeviceRepository devices;
    private final SnapshotRepository snapshots;
    private final KeyProvider keyProvider;
    private final CanonicalJsonHasher hasher;
    private final RateLimiter rateLimiter;

    public UsageSnapshotService(RegistrationRepository registrations, DeviceRepository devices,
                                SnapshotRepository snapshots, KeyProvider keyProvider,
                                CanonicalJsonHasher hasher,
                                RateLimiter rateLimiter) {
        this.registrations = registrations;
        this.devices = devices;
        this.snapshots = snapshots;
        this.keyProvider = keyProvider;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public UsageDtos.UploadResponse upload(UsageDtos.Snapshot snapshot, String idempotencyKey,
                                          ParticipantPrincipal participant, byte[] rawPayload) {
        rateLimiter.check("upload:registration:" + participant.registrationId(), 60);
        if (idempotencyKey == null || !snapshot.snapshotId().toString().equals(idempotencyKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency_key_mismatch",
                    "The idempotency key does not match the snapshot.");
        }
        if (!participant.registrationId().equals(snapshot.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "The registration is not eligible.");
        }
        String registrationStatus = registrations.lockStatus(participant.registrationId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                        "The registration is not eligible."));
        if (!"activated".equals(registrationStatus)
                || devices.findActive(participant.deviceId(), participant.registrationId()).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "The registration is not eligible.");
        }
        validateSemantics(snapshot);

        byte[] bodyHash = hasher.hash(snapshot);
        var existing = snapshots.find(snapshot.snapshotId());
        if (existing.isPresent()) {
            return duplicateOrConflict(existing.get(), bodyHash, snapshot.snapshotId());
        }

        var encryptedPayload = keyProvider.encrypt(rawPayload);
        boolean inserted = snapshots.insert(snapshot, participant.registrationId(), participant.deviceId(),
                bodyHash, encryptedPayload);
        if (!inserted) {
            var conflicting = snapshots.find(snapshot.snapshotId());
            if (conflicting.isPresent()) {
                return duplicateOrConflict(conflicting.get(), bodyHash, snapshot.snapshotId());
            }
            throw new ApiException(HttpStatus.CONFLICT, "scheduled_exists",
                    "A scheduled snapshot already exists for this date.");
        }
        return new UsageDtos.UploadResponse(snapshot.snapshotId(), "stored",
                snapshots.find(snapshot.snapshotId()).orElseThrow().receivedAt());
    }

    public UsageDtos.SnapshotStatus getStatus(UUID snapshotId, ParticipantPrincipal participant) {
        return snapshots.findOwned(snapshotId, participant.registrationId())
                .map(record -> new UsageDtos.SnapshotStatus(record.id(), "stored", record.receivedAt()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found",
                        "The requested resource was not found."));
    }

    private UsageDtos.UploadResponse duplicateOrConflict(
            SnapshotRepository.SnapshotRecord existing, byte[] requestHash, UUID snapshotId) {
        if (!MessageDigest.isEqual(existing.bodyHash(), requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "snapshot_id_conflict",
                    "The snapshot identifier was already used for a different payload.");
        }
        return new UsageDtos.UploadResponse(snapshotId, "duplicate", existing.receivedAt());
    }

    private void validateSemantics(UsageDtos.Snapshot snapshot) {
        try {
            ZoneId zone = ZoneId.of(snapshot.timezone());
            if (!snapshot.window().start().isBefore(snapshot.window().end())
                    || !snapshot.window().start().toLocalDate().equals(snapshot.date())
                    || !snapshot.window().start().toInstant().atZone(zone).toLocalDate().equals(snapshot.date())
                    || snapshot.window().start().getOffset().getTotalSeconds() / 60
                    != snapshot.utcOffsetMinutes()
                    || zone.getRules().getOffset(snapshot.window().start().toInstant())
                    .getTotalSeconds() / 60 != snapshot.utcOffsetMinutes()
                    || zone.getRules().getOffset(snapshot.window().end().toInstant())
                    .getTotalSeconds() / 60 != snapshot.window().end().getOffset().getTotalSeconds() / 60) {
                throw invalidPayload();
            }
        } catch (DateTimeException exception) {
            throw invalidPayload();
        }

        var totals = snapshot.totals();
        boolean notificationCountPresent = totals.notificationCount() != null;
        if ((totals.notificationSource() == UsageDtos.NotificationSource.unavailable) == notificationCountPresent
                || totals.notificationCountSynthetic()
                != (totals.notificationSource() == UsageDtos.NotificationSource.synthetic)
                || totals.activeAppCount() != snapshot.apps().size()) {
            throw invalidPayload();
        }

        var packageNames = new HashSet<String>();
        int previousForegroundSeconds = Integer.MAX_VALUE;
        for (var app : snapshot.apps()) {
            if (!packageNames.add(app.packageName())
                    || app.foregroundSeconds() == 0 && app.launchCount() == 0
                    || app.foregroundSeconds() > previousForegroundSeconds) {
                throw invalidPayload();
            }
            previousForegroundSeconds = app.foregroundSeconds();
        }
    }

    private ApiException invalidPayload() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_payload",
                "The snapshot does not match the required format.");
    }
}
