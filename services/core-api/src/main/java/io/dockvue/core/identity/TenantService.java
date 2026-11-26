package io.dockvue.core.identity;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TenantService {
    enum Role { LEARNER, CREATOR, TENANT_ADMIN }
    record Tenant(String id,String slug,String name,String status,long version,Role role) {}
    record Member(String userId,Role role,String status,long version) {}
    record Invitation(String id,String status) {}
    record ApprovalState(String userId,boolean approved,boolean emailVerified,long version) {}
    record InviteRow(String id,String tenant,String email,Role role,boolean usable) {}
    record Idempotent(String hash,String tenantId) {}
    private final JdbcTemplate db; private final IdentityService identity; private final Audit audit; private final IdentityMail mail;
    TenantService(JdbcTemplate db,IdentityService identity,Audit audit,IdentityMail mail) {this.db=db;this.identity=identity;this.audit=audit;this.mail=mail;}
    private void lockTenant(String tenant) {
        if(db.query("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",(r,n)->r.getString(1),tenant).isEmpty())throw ApiError.missing();
    }
    Member membership(String actor,String tenant,Role minimum) {
        identity.current(actor);
        Member m=db.query("SELECT m.user_id,m.role,m.status,m.version FROM memberships m JOIN tenants t ON t.id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE'",
            (r,n)->new Member(r.getString(1),Role.valueOf(r.getString(2)),r.getString(3),r.getLong(4)),tenant,actor).stream().findFirst().orElseThrow(ApiError::missing);
        if(m.role.ordinal()<minimum.ordinal())throw ApiError.forbidden();
        return m;
    }
    // Reused by later content/registry endpoints; global platform role never bypasses tenant membership.
    public void requireCreator(String actor,String tenant) {
        membership(actor,tenant,Role.CREATOR);
        IdentityService.User u=identity.current(actor);
        if(!u.verified()||!u.approved())throw ApiError.forbidden();
    }
    public void requireMember(String actor,String tenant) { membership(actor,tenant,Role.LEARNER); }
    public void requireAdmin(String actor,String tenant) { membership(actor,tenant,Role.TENANT_ADMIN); }
    List<Tenant> list(String actor) {
        identity.current(actor);
        return db.query("SELECT t.id,t.slug,t.name,t.status,t.version,m.role FROM tenants t JOIN memberships m ON t.id=m.tenant_id WHERE m.user_id=? AND m.status='ACTIVE' AND t.status='ACTIVE' ORDER BY t.id LIMIT 100",
            (r,n)->new Tenant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),Role.valueOf(r.getString(6))),actor);
    }
    Tenant get(String actor,String tenant) {
        identity.current(actor);
        return db.query("SELECT t.id,t.slug,t.name,t.status,t.version,m.role FROM tenants t JOIN memberships m ON t.id=m.tenant_id WHERE t.id=? AND m.user_id=? AND t.status='ACTIVE' AND m.status='ACTIVE'",
            (r,n)->new Tenant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),Role.valueOf(r.getString(6))),tenant,actor).stream().findFirst().orElseThrow(ApiError::missing);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Tenant create(String actor,String name,String slug,String key,String trace) {
        IdentityService.User u=identity.lockCurrent(actor);
        if(!u.verified())throw ApiError.forbidden();
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw ApiError.bad("INVALID_IDEMPOTENCY_KEY");
        String hash=Secrets.hash(name+"\n"+slug);
        db.update("DELETE FROM idempotency_keys WHERE actor_id=? AND tenant_scope='global' AND route='createTenant' AND request_key=? AND expires_at<=UTC_TIMESTAMP(6)",actor,key);
        Idempotent prior=db.query("SELECT request_hash,JSON_UNQUOTE(JSON_EXTRACT(response_json,'$.id')) FROM idempotency_keys WHERE actor_id=? AND tenant_scope='global' AND route='createTenant' AND request_key=?",
            (r,n)->new Idempotent(r.getString(1),r.getString(2)),actor,key).stream().findFirst().orElse(null);
        if(prior!=null) {
            if(!prior.hash.equals(hash))throw new ApiError(409,"IDEMPOTENCY_CONFLICT");
            return get(actor,prior.tenantId);
        }
        String id=Secrets.id();
        db.update("INSERT INTO tenants(id,slug,name,quota_json) VALUES(?,?,?,JSON_OBJECT())",id,slug,name);
        db.update("INSERT INTO memberships(tenant_id,user_id,role) VALUES(?,?,'TENANT_ADMIN')",id,actor);
        db.update("INSERT INTO idempotency_keys(actor_id,tenant_scope,route,request_key,request_hash,state,response_json,expires_at) VALUES(?,'global','createTenant',?,?,'DONE',JSON_OBJECT('id',?),UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)",actor,key,hash,id);
        audit.record(actor,id,"TENANT_CREATED","TENANT",id,trace);
        return get(actor,id);
    }
    List<Member> members(String actor,String tenant) {
        membership(actor,tenant,Role.TENANT_ADMIN);
        return db.query("SELECT user_id,role,status,version FROM memberships WHERE tenant_id=? ORDER BY user_id LIMIT 100",
            (r,n)->new Member(r.getString(1),Role.valueOf(r.getString(2)),r.getString(3),r.getLong(4)),tenant);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Invitation invite(String actor,String tenant,String email,Role role,String trace) {
        lockTenant(tenant);membership(actor,tenant,Role.TENANT_ADMIN);
        // Reinviting invalidates earlier pending invitation, not an existing membership.
        db.update("UPDATE invitations SET consumed_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND email=? AND consumed_at IS NULL",tenant,email);
        String id=Secrets.id(),raw=Secrets.token();
        db.update("INSERT INTO invitations(id,tenant_id,email,token_hash,role,expires_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6)+INTERVAL 48 HOUR)",id,tenant,email,Secrets.hash(raw),role.name());
        audit.record(actor,tenant,"INVITATION_CREATED","INVITATION",id,trace);
        mail.afterCommit(email,"tenant invitation",raw);
        return new Invitation(id,"PENDING");
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Tenant accept(String actor,String raw,String trace) {
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw ApiError.missing();
        String hash=Secrets.hash(raw);
        String tenant=db.query("SELECT tenant_id FROM invitations WHERE token_hash=?",(r,n)->r.getString(1),hash).stream().findFirst().orElseThrow(ApiError::missing);
        lockTenant(tenant);
        IdentityService.User u=identity.current(actor);
        InviteRow i=db.query("SELECT id,tenant_id,email,role,(consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6)) FROM invitations WHERE token_hash=? FOR UPDATE",
            (r,n)->new InviteRow(r.getString(1),r.getString(2),r.getString(3),Role.valueOf(r.getString(4)),r.getBoolean(5)),hash).stream().findFirst().orElseThrow(ApiError::missing);
        if(!u.verified()||!u.email().equals(i.email)||!i.usable)throw ApiError.missing();
        // An invite cannot silently alter an existing membership's role.
        if(!db.query("SELECT user_id FROM memberships WHERE tenant_id=? AND user_id=?",(r,n)->r.getString(1),tenant,actor).isEmpty())
            throw new ApiError(409,"MEMBERSHIP_EXISTS");
        db.update("INSERT INTO memberships(tenant_id,user_id,role) VALUES(?,?,?)",tenant,actor,i.role.name());
        db.update("UPDATE invitations SET consumed_at=UTC_TIMESTAMP(6) WHERE id=?",i.id);
        audit.record(actor,tenant,"INVITATION_ACCEPTED","INVITATION",i.id,trace);
        return get(actor,tenant);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Member change(String actor,String tenant,String target,Role role,String status,long expected,String trace) {
        lockTenant(tenant);membership(actor,tenant,Role.TENANT_ADMIN);
        Member m=db.query("SELECT user_id,role,status,version FROM memberships WHERE tenant_id=? AND user_id=? FOR UPDATE",
            (r,n)->new Member(r.getString(1),Role.valueOf(r.getString(2)),r.getString(3),r.getLong(4)),tenant,target).stream().findFirst().orElseThrow(ApiError::missing);
        if(m.version!=expected)throw new ApiError(412,"STALE_VERSION");
        if(m.role==Role.TENANT_ADMIN&&m.status.equals("ACTIVE")&&(role!=Role.TENANT_ADMIN||!status.equals("ACTIVE"))) {
            Integer count=db.queryForObject("SELECT COUNT(*) FROM memberships WHERE tenant_id=? AND role='TENANT_ADMIN' AND status='ACTIVE'",Integer.class,tenant);
            if(count==null||count<=1)throw new ApiError(409,"LAST_TENANT_ADMIN");
        }
        db.update("UPDATE memberships SET role=?,status=?,version=version+1 WHERE tenant_id=? AND user_id=?",role.name(),status,tenant,target);
        audit.membership(actor,tenant,target,trace,m.role.name(),m.status,role.name(),status);
        return new Member(target,role,status,expected+1);
    }
    private void requirePlatformAdmin(String actor) {
        IdentityService.User admin=identity.current(actor);
        if(!admin.platformRole().equals("PLATFORM_ADMIN")||!admin.verified())throw ApiError.forbidden();
    }
    ApprovalState approval(String actor,String target) {
        requirePlatformAdmin(actor);
        return db.query("SELECT id,creator_approved,email_verified_at,version FROM users WHERE id=? AND status='ACTIVE'",
            (r,n)->new ApprovalState(r.getString(1),r.getBoolean(2),r.getTimestamp(3)!=null,r.getLong(4)),target).stream().findFirst().orElseThrow(ApiError::missing);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApprovalState approve(String actor,String target,boolean approved,long expected,String trace) {
        requirePlatformAdmin(actor);
        IdentityService.User u=identity.lockCurrent(target);
        ApprovalState state=approval(actor,target);
        if(state.version!=expected)throw new ApiError(412,"STALE_VERSION");
        if(approved&&!u.verified())throw new ApiError(409,"EMAIL_UNVERIFIED");
        if(u.approved()==approved)return state;
        db.update("UPDATE users SET creator_approved=?,version=version+1 WHERE id=?",approved,target);
        audit.record(actor,null,approved?"CREATOR_APPROVED":"CREATOR_REVOKED","USER",target,trace);
        return new ApprovalState(target,approved,u.verified(),expected+1);
    }
}
