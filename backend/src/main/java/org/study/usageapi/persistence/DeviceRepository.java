package org.study.usageapi.persistence;

import org.study.usageapi.api.DeviceDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Device> findActiveByRegistration(String registrationId) {
        return jdbc.query("""
                SELECT id, registration_id, public_key_jwk::text, app_version, status
                FROM devices WHERE registration_id = ? AND status = 'active'
                """, (rs, row) -> new Device(rs.getObject("id", UUID.class),
                rs.getString("registration_id"), rs.getString("public_key_jwk"),
                rs.getString("app_version"), rs.getString("status")), registrationId)
                .stream().findFirst();
    }

    public Optional<Device> findActive(UUID deviceId, String registrationId) {
        return jdbc.query("""
                SELECT id, registration_id, public_key_jwk::text, app_version, status
                FROM devices WHERE id = ? AND registration_id = ? AND status = 'active'
                """, (rs, row) -> new Device(rs.getObject("id", UUID.class),
                rs.getString("registration_id"), rs.getString("public_key_jwk"),
                rs.getString("app_version"), rs.getString("status")), deviceId, registrationId)
                .stream().findFirst();
    }

    public Optional<Device> findById(UUID deviceId) {
        return jdbc.query("""
                SELECT id, registration_id, public_key_jwk::text, app_version, status
                FROM devices WHERE id = ?
                """, (rs, row) -> new Device(rs.getObject("id", UUID.class),
                rs.getString("registration_id"), rs.getString("public_key_jwk"),
                rs.getString("app_version"), rs.getString("status")), deviceId)
                .stream().findFirst();
    }

    public void insert(UUID id, String registrationId, DeviceDtos.PublicKeyJwk publicKey, String appVersion) {
        jdbc.update("""
                INSERT INTO devices (id, registration_id, public_key_jwk, app_version)
                VALUES (?, ?, ?::jsonb, ?)
                """, id, registrationId,
                "{\"kty\":\"" + publicKey.kty() + "\",\"crv\":\"" + publicKey.crv()
                        + "\",\"x\":\"" + publicKey.x() + "\",\"y\":\"" + publicKey.y() + "\"}",
                appVersion);
    }

    public int touch(UUID deviceId) {
        return jdbc.update("UPDATE devices SET last_seen_at = now() WHERE id = ? AND status = 'active'", deviceId);
    }

    public int revokeActive(UUID deviceId) {
        return jdbc.update("""
                UPDATE devices SET status = 'revoked', revoked_at = now()
                WHERE id = ? AND status = 'active'
                """, deviceId);
    }

    public record Device(UUID id, String registrationId, String publicKeyJson, String appVersion, String status) {
    }
}
