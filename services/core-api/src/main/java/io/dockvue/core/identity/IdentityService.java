package io.dockvue.core.identity;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class IdentityService {
    record User(String id,String email,String password,String status,boolean verified,boolean approved,String platformRole) {}
    record Session(String userId,String refresh,Instant expiresAt) {}
    record Rotation(Session session,String failure) {}
    record Family(String id,String userId,Instant expiresAt,boolean revoked) {}
    record Refresh(String id,boolean used,boolean revoked) {}
    final JdbcTemplate db; final PasswordEncoder passwords; final IdentityMail mail; final Audit audit;
    private final String dummy;
    IdentityService(JdbcTemplate db,PasswordEncoder passwords,IdentityMail mail,Audit audit) {
        this.db=db;this.passwords=passwords;this.mail=mail;this.audit=audit;this.dummy=passwords.encode(Secrets.token());
    }
    private User userBy(String column,String value,boolean lock) {
        // Column is selected only by internal call sites, never from a request.
        return db.query("SELECT id,normalized_email,password_hash,status,email_verified_at,creator_approved,platform_role FROM users WHERE "+column+"=?"+(lock?" FOR UPDATE":""),
          (r,n)->new User(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5)!=null,r.getBoolean(6),r.getString(7)),value).stream().findFirst().orElse(null);
    }
    User current(String id) {
        User u=userBy("id",id,false);
        if(u==null||!u.status.equals("ACTIVE")) throw ApiError.unauthorized();
        return u;
    }
    User lockCurrent(String id) {
        User u=userBy("id",id,true);
        if(u==null||!u.status.equals("ACTIVE")) throw ApiError.unauthorized();
        return u;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void register(String email,String password,String trace) {
        String hash=passwords.encode(password);
        String id=Secrets.id();
        db.update("INSERT INTO users(id,normalized_email,password_hash) VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id",id,email,hash);
        // JDBC affected-row settings vary; use identity comparison, not row count.
        User u=userBy("normalized_email",email,true);
        if(u!=null&&u.id.equals(id)) { verification(u,trace); audit.record(id,null,"REGISTER","USER",id,trace); }
    }
    private void verification(User u,String trace) {
        String code=Secrets.token();
        db.update("UPDATE email_verifications SET consumed_at=UTC_TIMESTAMP(6) WHERE user_id=? AND consumed_at IS NULL",u.id);
        db.update("INSERT INTO email_verifications(token_hash,user_id,expires_at) VALUES(?,?,UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE)",Secrets.hash(code),u.id);
        mail.afterCommit(u.email,"email verification",code);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void resend(String email,String trace) {
        User u=userBy("normalized_email",email,true);
        if(u!=null&&!u.verified&&u.status.equals("ACTIVE")) verification(u,trace);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void verify(String actor,String raw,String trace) {
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw ApiError.bad("INVALID_CODE");
        String hash=Secrets.hash(raw);
        String id=db.query("SELECT user_id FROM email_verifications WHERE token_hash=?",(r,n)->r.getString(1),hash).stream().findFirst().orElseThrow(()->ApiError.bad("INVALID_CODE"));
        if(!id.equals(actor))throw ApiError.bad("INVALID_CODE");
        lockCurrent(id);
        if(db.update("UPDATE email_verifications SET consumed_at=UTC_TIMESTAMP(6) WHERE token_hash=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",hash)!=1)
            throw ApiError.bad("INVALID_CODE");
        db.update("UPDATE users SET email_verified_at=COALESCE(email_verified_at,UTC_TIMESTAMP(6)),version=version+1 WHERE id=?",id);
        audit.record(id,null,"EMAIL_VERIFIED","USER",id,trace);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Session login(String email,String password,String trace) {
        User u=userBy("normalized_email",email,false);
        boolean matches=passwords.matches(password,u==null?dummy:u.password);
        if(!matches||u==null||!u.status.equals("ACTIVE")) { audit.record(u==null?null:u.id,null,"LOGIN_FAILED","USER",u==null?null:u.id,trace); return null; }
        String family=Secrets.id(); Instant expires=Instant.now().plus(Duration.ofDays(30));
        db.update("INSERT INTO refresh_families(id,user_id,expires_at) VALUES(?,?,?)",family,u.id,java.sql.Timestamp.from(expires));
        Session session=newToken(u.id,family,expires,null);
        audit.record(u.id,null,"LOGIN","USER",u.id,trace);
        return session;
    }
    private Session newToken(String user,String family,Instant expiry,String previous) {
        String raw=Secrets.token(),id=Secrets.id();
        db.update("INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at) VALUES(?,?,?,?,?)",id,user,family,Secrets.hash(raw),java.sql.Timestamp.from(expiry));
        if(previous!=null) db.update("UPDATE refresh_tokens SET used_at=UTC_TIMESTAMP(6),replaced_by=? WHERE id=?",id,previous);
        return new Session(user,raw,expiry);
    }
    private Family family(String hash) {
        String id=db.query("SELECT family_id FROM refresh_tokens WHERE token_hash=?",(r,n)->r.getString(1),hash).stream().findFirst().orElse(null);
        if(id==null)return null;
        return db.query("SELECT id,user_id,expires_at,revoked_at FROM refresh_families WHERE id=? FOR UPDATE",
          (r,n)->new Family(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4)!=null),id).stream().findFirst().orElse(null);
    }
    private void revoke(Family f) {
        db.update("UPDATE refresh_families SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE id=?",f.id);
        db.update("UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(6)) WHERE family_id=?",f.id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Rotation rotate(String raw,String trace) {
        String hash=Secrets.hash(Secrets.opaque(raw)); Family f=family(hash);
        if(f==null||f.revoked||!f.expiresAt.isAfter(Instant.now())) return new Rotation(null,"INVALID_REFRESH");
        Refresh t=db.query("SELECT id,used_at,revoked_at FROM refresh_tokens WHERE token_hash=? AND family_id=?",
            (r,n)->new Refresh(r.getString(1),r.getTimestamp(2)!=null,r.getTimestamp(3)!=null),hash,f.id).stream().findFirst().orElseThrow();
        if(t.used||t.revoked) {
            revoke(f); audit.record(f.userId,null,"REFRESH_REPLAY","REFRESH_FAMILY",f.id,trace);
            // Return failure, don't throw inside transaction: revocation MUST commit.
            return new Rotation(null,"REFRESH_REPLAY");
        }
        User u=userBy("id",f.userId,false);
        if(u==null||!u.status.equals("ACTIVE")) {revoke(f);return new Rotation(null,"INVALID_REFRESH");}
        return new Rotation(newToken(f.userId,f.id,f.expiresAt,t.id),null);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void logout(String raw,String trace) {
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))return;
        Family f=family(Secrets.hash(raw));
        if(f!=null&&!f.revoked) {revoke(f);audit.record(f.userId,null,"LOGOUT","REFRESH_FAMILY",f.id,trace);}
    }
}
