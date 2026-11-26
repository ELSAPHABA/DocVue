package io.dockvue.core.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class Audit {
    private final JdbcTemplate db;
    Audit(JdbcTemplate db) { this.db = db; }
    void record(String actor, String tenant, String action, String targetType, String target, String trace) {
        db.update("INSERT INTO audit_events(id,tenant_id,actor_id,action,target_type,target_id,trace_id,sanitized_details) VALUES(?,?,?,?,?,?,?,JSON_OBJECT())",
            Secrets.id(),tenant,actor,action,targetType,target,trace);
    }
    void membership(String actor,String tenant,String target,String trace,String beforeRole,String beforeStatus,String afterRole,String afterStatus) {
        db.update("INSERT INTO audit_events(id,tenant_id,actor_id,action,target_type,target_id,trace_id,sanitized_details) VALUES(?,?,?,'MEMBERSHIP_CHANGED','USER',?,?,JSON_OBJECT('beforeRole',?,'beforeStatus',?,'afterRole',?,'afterStatus',?))",
            Secrets.id(),tenant,actor,target,trace,beforeRole,beforeStatus,afterRole,afterStatus);
    }
}
