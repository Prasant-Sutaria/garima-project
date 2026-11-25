package org.study.usageapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.UsageDtos;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.crypto.EncryptedEnvelope;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;
import org.study.usageapi.persistence.SnapshotRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageSnapshotServiceTest {

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final SnapshotRepository snapshots = mock(SnapshotRepository.class);
    private final KeyProvider keyProvider = mock(KeyProvider.class);
    private final CanonicalJsonHasher hasher = mock(CanonicalJsonHasher.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private UsageSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new UsageSnapshotService(registrations, devices, snapshots, keyProvider,
                hasher, rateLimiter);
    }

    @Test
    void identicalSnapshotRetryReturnsDuplicateWithoutReinserting() {
        UUID snapshotId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] hash = {1, 2, 3};
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-11-20T18:25:00Z");
        var request = snapshot(snapshotId);
        var principal = new ParticipantPrincipal("P-7K3Q9X", deviceId);
        when(registrations.lockStatus(principal.registrationId())).thenReturn(Optional.of("activated"));
        when(devices.findActive(deviceId, principal.registrationId())).thenReturn(Optional.of(
                new DeviceRepository.Device(deviceId, principal.registrationId(), "{}", "1.0.0", "active")));
        when(hasher.hash(request)).thenReturn(hash);
        when(snapshots.find(snapshotId)).thenReturn(Optional.of(new SnapshotRepository.SnapshotRecord(
                snapshotId, principal.registrationId(), hash, receivedAt)));

        var result = service.upload(request, snapshotId.toString(), principal, new byte[]{9});

        assertEquals("duplicate", result.status());
        assertEquals(receivedAt, result.receivedAt());
        verify(snapshots, never()).insert(any(UsageDtos.Snapshot.class), anyString(),
                any(UUID.class), any(byte[].class), any(EncryptedEnvelope.class));
        verify(keyProvider, never()).encrypt(any(byte[].class));
    }

    @Test
    void reusedSnapshotIdWithDifferentBodyReturnsConflict() {
        UUID snapshotId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        var request = snapshot(snapshotId);
        var principal = new ParticipantPrincipal("P-7K3Q9X", deviceId);
        when(registrations.lockStatus(principal.registrationId())).thenReturn(Optional.of("activated"));
        when(devices.findActive(deviceId, principal.registrationId())).thenReturn(Optional.of(
                new DeviceRepository.Device(deviceId, principal.registrationId(), "{}", "1.0.0", "active")));
        when(hasher.hash(request)).thenReturn(new byte[]{1});
        when(snapshots.find(snapshotId)).thenReturn(Optional.of(new SnapshotRepository.SnapshotRecord(
                snapshotId, principal.registrationId(), new byte[]{2},
                OffsetDateTime.parse("2026-11-20T18:25:00Z"))));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.upload(request, snapshotId.toString(), principal, new byte[]{9}));

        assertEquals(409, exception.status().value());
        assertEquals("snapshot_id_conflict", exception.code());
    }

    @Test
    void scheduledConflictWithoutSnapshotIdReturnsScheduledExists() {
        UUID snapshotId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        var request = snapshot(snapshotId);
        var principal = new ParticipantPrincipal("P-7K3Q9X", deviceId);
        when(registrations.lockStatus(principal.registrationId())).thenReturn(Optional.of("activated"));
        when(devices.findActive(deviceId, principal.registrationId())).thenReturn(Optional.of(
                new DeviceRepository.Device(deviceId, principal.registrationId(), "{}", "1.0.0", "active")));
        when(hasher.hash(request)).thenReturn(new byte[]{1});
        when(snapshots.find(snapshotId)).thenReturn(Optional.empty());
        when(keyProvider.encrypt(any(byte[].class))).thenReturn(new EncryptedEnvelope(
                new byte[]{1}, new byte[]{2}, "local-v1"));
        when(snapshots.insert(eq(request), eq(principal.registrationId()), eq(deviceId),
                any(byte[].class), any(EncryptedEnvelope.class)))
                .thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.upload(request, snapshotId.toString(), principal, new byte[]{9}));

        assertEquals(409, exception.status().value());
        assertEquals("scheduled_exists", exception.code());
    }

    @Test
    void storesTheOriginalRequestBytesInsideTheEncryptedEnvelope() {
        UUID snapshotId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] rawPayload = " { \"sample\": true } ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bodyHash = {4, 5, 6};
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-11-20T18:25:00Z");
        var request = snapshot(snapshotId);
        var principal = new ParticipantPrincipal("P-7K3Q9X", deviceId);
        var encrypted = new EncryptedEnvelope(new byte[]{1}, new byte[]{2}, "local-v1");
        when(registrations.lockStatus(principal.registrationId())).thenReturn(Optional.of("activated"));
        when(devices.findActive(deviceId, principal.registrationId())).thenReturn(Optional.of(
                new DeviceRepository.Device(deviceId, principal.registrationId(), "{}", "1.0.0", "active")));
        when(hasher.hash(request)).thenReturn(bodyHash);
        when(snapshots.find(snapshotId)).thenReturn(Optional.empty(),
                Optional.of(new SnapshotRepository.SnapshotRecord(
                        snapshotId, principal.registrationId(), bodyHash, receivedAt)));
        when(keyProvider.encrypt(rawPayload)).thenReturn(encrypted);
        when(snapshots.insert(eq(request), eq(principal.registrationId()), eq(deviceId), eq(bodyHash), eq(encrypted)))
                .thenReturn(true);

        var result = service.upload(request, snapshotId.toString(), principal, rawPayload);

        assertEquals("stored", result.status());
        verify(keyProvider).encrypt(rawPayload);
    }

    private UsageDtos.Snapshot snapshot(UUID id) {
        return new UsageDtos.Snapshot(
                "1.0", id, UsageDtos.Trigger.scheduled, java.time.LocalDate.parse("2026-11-20"),
                "P-7K3Q9X", "1.0.0", "Asia/Kolkata", 330,
                new UsageDtos.UsageWindow(
                        OffsetDateTime.parse("2026-11-20T00:00:00+05:30"),
                        OffsetDateTime.parse("2026-11-20T23:55:00+05:30")),
                OffsetDateTime.parse("2026-11-20T23:55:02+05:30"),
                new UsageDtos.Totals(14520, 63, 88, UsageDtos.NotificationSource.measured, false, 1),
                List.of(new UsageDtos.AppUsage("com.example.app", 4210, 18)),
                new UsageDtos.Quality(412, 1, 0));
    }
}
