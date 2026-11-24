package org.study.usageapi.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String actor, String action, String targetId, String requestId, String outcome) {
        jdbc.update("""
                INSERT INTO audit_log (actor, action, target_id, request_id, outcome)
                VALUES (?, ?, ?, ?, ?)
                """, actor, action, targetId, requestId, outcome);
    }
}
