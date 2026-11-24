package org.study.usageapi.persistence;

import org.study.usageapi.api.UsageDtos;
import org.study.usageapi.crypto.EncryptedEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SnapshotRecord> find(UUID snapshotId) {
        return jdbc.query("""
                SELECT id, registration_id, body_sha256, received_at
                FROM usage_snapshots WHERE id = ?
                """, (rs, row) -> new SnapshotRecord(rs.getObject("id", UUID.class),
                rs.getString("registration_id"), rs.getBytes("body_sha256"),
                rs.getObject("received_at", OffsetDateTime.class)), snapshotId)
                .stream().findFirst();
    }

    public boolean insert(UsageDtos.Snapshot snapshot, String registrationId, UUID deviceId,
                          byte[] bodyHash, EncryptedEnvelope encryptedPayload) {
        int inserted = jdbc.update("""
                INSERT INTO usage_snapshots (
                    id, registration_id, device_id, schema_version, app_version, trigger,
                    usage_date, timezone, utc_offset_minutes, window_start, window_end,
                    collected_at, screen_on_seconds, unlock_count, notification_count,
                    notification_source, notification_count_synthetic, active_app_count,
                    open_sessions_closed, unmatched_events, event_count, body_sha256,
                    payload_enc, payload_dek_wrapped, payload_key_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                snapshot.snapshotId(), registrationId, deviceId, snapshot.schemaVersion(),
                snapshot.appVersion(), snapshot.trigger().name(), snapshot.date(), snapshot.timezone(),
                snapshot.utcOffsetMinutes(), snapshot.window().start(), snapshot.window().end(),
                snapshot.collectedAt(), snapshot.totals().screenOnSeconds(),
                snapshot.totals().unlockCount(), snapshot.totals().notificationCount(),
                snapshot.totals().notificationSource().name(),
                snapshot.totals().notificationCountSynthetic(), snapshot.totals().activeAppCount(),
                snapshot.quality().openSessionsClosed(), snapshot.quality().unmatchedEvents(),
                snapshot.quality().eventCount(), bodyHash, encryptedPayload.ciphertext(),
                encryptedPayload.wrappedDek(), encryptedPayload.keyId());
        if (inserted == 0) {
            return false;
        }
        jdbc.batchUpdate("""
                INSERT INTO app_usage (snapshot_id, package_name, foreground_seconds, launch_count)
                VALUES (?, ?, ?, ?)
                """, snapshot.apps(), snapshot.apps().size(),
                (statement, app) -> {
                    statement.setObject(1, snapshot.snapshotId());
                    statement.setString(2, app.packageName());
                    statement.setInt(3, app.foregroundSeconds());
                    statement.setInt(4, app.launchCount());
                });
        return true;
    }

    public Optional<SnapshotRecord> findOwned(UUID snapshotId, String registrationId) {
        return jdbc.query("""
                SELECT id, registration_id, body_sha256, received_at
                FROM usage_snapshots WHERE id = ? AND registration_id = ?
                """, (rs, row) -> new SnapshotRecord(rs.getObject("id", UUID.class),
                rs.getString("registration_id"), rs.getBytes("body_sha256"),
                rs.getObject("received_at", OffsetDateTime.class)), snapshotId, registrationId)
                .stream().findFirst();
    }

    public record SnapshotRecord(UUID id, String registrationId, byte[] bodyHash,
                                 OffsetDateTime receivedAt) {
    }
}
