package org.study.usageapi.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class RegistrationRepository {

    private final JdbcTemplate jdbc;

    public RegistrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findStatus(String registrationId) {
        return jdbc.query("SELECT status FROM registry.registrations WHERE registration_id = ?",
                        (resultSet, rowNumber) -> resultSet.getString("status"), registrationId)
                .stream().findFirst();
    }

    public Optional<String> lockStatus(String registrationId) {
        return jdbc.query("SELECT status FROM registry.registrations WHERE registration_id = ? FOR UPDATE",
                        (resultSet, rowNumber) -> resultSet.getString("status"), registrationId)
                .stream().findFirst();
    }

    public void insert(String registrationId, byte[] ageEncrypted, byte[] genderEncrypted,
                       byte[] wrappedDek, String keyId) {
        jdbc.update("""
                INSERT INTO registry.registrations
                    (registration_id, age_enc, gender_enc, dek_wrapped, key_id)
                VALUES (?, ?, ?, ?, ?)
                """, registrationId, ageEncrypted, genderEncrypted, wrappedDek, keyId);
    }

    public boolean insertGenerated(String registrationId, byte[] ageEncrypted, byte[] genderEncrypted,
                                   byte[] wrappedDek, String keyId) {
        return jdbc.update("""
                INSERT INTO registry.registrations
                    (registration_id, age_enc, gender_enc, dek_wrapped, key_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (registration_id) DO NOTHING
                """, registrationId, ageEncrypted, genderEncrypted, wrappedDek, keyId) == 1;
    }

    public boolean isSchemeActive(int durationMonths) {
        return jdbc.query("SELECT active FROM registry.schemes WHERE duration_months = ?",
                        (resultSet, rowNumber) -> resultSet.getBoolean("active"), durationMonths)
                .stream().findFirst().orElse(false);
    }

    public boolean insertEnrollmentDetails(String registrationId, byte[] ciphertext, byte[] wrappedDek,
                                           String keyId, byte[] emailFingerprint, byte[] mobileFingerprint,
                                           int schemeMonths, String profession) {
        return jdbc.update("""
                INSERT INTO registry.enrollment_details
                    (registration_id, details_enc, dek_wrapped, key_id, email_fingerprint, mobile_fingerprint,
                     scheme_months, profession)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, registrationId, ciphertext, wrappedDek, keyId, emailFingerprint, mobileFingerprint,
                schemeMonths, profession) == 1;
    }

    public byte[] contactIndexVerifier(byte[] verifier) {
        jdbc.update("""
                INSERT INTO registry.contact_index_key (singleton, verifier)
                SELECT TRUE, ?
                WHERE NOT EXISTS (SELECT 1 FROM registry.enrollment_details)
                ON CONFLICT (singleton) DO NOTHING
                """, verifier);
        return jdbc.query("SELECT verifier FROM registry.contact_index_key WHERE singleton = TRUE",
                        (resultSet, rowNumber) -> resultSet.getBytes("verifier"))
                .stream().findFirst().orElse(null);
    }

    public int updateStatus(String registrationId, String status) {
        return jdbc.update("UPDATE registry.registrations SET status = ? WHERE registration_id = ?",
                status, registrationId);
    }
}
