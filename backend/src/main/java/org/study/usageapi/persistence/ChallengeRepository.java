package org.study.usageapi.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class ChallengeRepository {

    private final JdbcTemplate jdbc;

    public ChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(byte[] nonceHash, String registrationId, Instant issuedAt, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO auth_challenges (nonce_hash, registration_id, issued_at, expires_at)
                VALUES (?, ?, ?, ?)
                """, nonceHash, registrationId, toUtc(issuedAt), toUtc(expiresAt));
    }

    public boolean consume(byte[] nonceHash, String registrationId, Instant now) {
        return !jdbc.query("""
                UPDATE auth_challenges SET used_at = ?
                WHERE nonce_hash = ? AND registration_id = ? AND used_at IS NULL AND expires_at > ?
                RETURNING nonce_hash
                """, (rs, row) -> rs.getBytes(1),
                toUtc(now), nonceHash, registrationId, toUtc(now)).isEmpty();
    }

    public int deleteExpired() {
        return jdbc.update("DELETE FROM auth_challenges WHERE expires_at <= now()");
    }

    private OffsetDateTime toUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
