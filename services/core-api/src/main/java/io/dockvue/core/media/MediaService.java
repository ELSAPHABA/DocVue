package io.dockvue.core.media;

import static io.dockvue.core.media.MediaModels.*;
import io.dockvue.core.identity.ApiError;
import io.dockvue.core.identity.TenantService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import io.dockvue.core.media.MediaModels.Part;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Service
class MediaService {
    static final long PART=16L*1024*1024;
    static final String BUCKET="dockvue-media";
    final JdbcTemplate db; final TenantService tenants; final S3Client s3; final S3Presigner signer;
    final TransactionTemplate tx; final boolean enabled,dev;
    record Session(String id,String tenant,String video,String actor,String media,String key,String provider,long bytes,String sha,String status,String hash,String job,boolean expired){}
    private static final String SESSION_SQL="""
        SELECT u.id,u.tenant_id,u.video_id,u.actor_id,u.media_id,m.original_key,u.provider_upload_id,m.expected_bytes,m.full_sha256,u.status,u.request_hash,u.job_id,
        u.expires_at<=UTC_TIMESTAMP(6) FROM upload_sessions u JOIN media_versions m ON m.id=u.media_id
        """;
    MediaService(JdbcTemplate db,TenantService tenants,S3Client s3,S3Presigner signer,PlatformTransactionManager tm,
        @Value("${dockvue.media.enabled:false}") boolean enabled,@Value("${dockvue.auth.dev:false}") boolean dev){
        this.db=db;this.tenants=tenants;this.s3=s3;this.signer=signer;this.enabled=enabled;this.dev=dev;
        this.tx=new TransactionTemplate(tm);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void available(){if(!enabled)throw new ApiError(503,"MEDIA_DISABLED");}
    static String id(){return UUID.randomUUID().toString();}
    static String hash(String s){return HexFormat.of().formatHex(digest(s.getBytes(StandardCharsets.UTF_8)));}
    static byte[] digest(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static int count(long bytes){return (int)((bytes+PART-1)/PART);}
    static long size(long bytes,int n){return Math.min(PART,bytes-(n-1L)*PART);}
    void authorize(String actor,String tenant,String video,boolean lock){
        boolean admin=lock?tenants.lockCreator(actor,tenant):tenants.canManageAll(actor,tenant);
        String owner=db.query("SELECT created_by FROM videos WHERE id=? AND tenant_id=? AND status='DRAFT'"+(lock?" FOR UPDATE":""),
            (r,n)->r.getString(1),video,tenant).stream().findFirst().orElseThrow(ApiError::missing);
        if(!admin&&!owner.equals(actor))throw ApiError.missing();
    }
    private Session get(String id,boolean lock){
        return db.query(SESSION_SQL+" WHERE u.id=?"+(lock?" FOR UPDATE":""),(r,n)->new Session(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getBoolean(13)),id)
            .stream().findFirst().orElseThrow(ApiError::missing);
    }
    private Session own(String actor,String tenant,String video,String id,boolean lock){
        authorize(actor,tenant,video,lock);Session u=get(id,lock);
        if(!u.actor.equals(actor)||!u.tenant.equals(tenant)||!u.video.equals(video))throw ApiError.missing();return u;
    }
    private void open(Session u){if(u.expired||!u.status.equals("OPEN"))throw new ApiError(409,"UPLOAD_NOT_OPEN");}
    Upload start(String actor,String tenant,String video,Start input,String key){
        available();
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw ApiError.bad("INVALID_IDEMPOTENCY_KEY");
        String ext=input.filename().toLowerCase(Locale.ROOT);
        if(!ext.matches("(?s).+\\.(mp4|mov|webm)")||input.filename().chars().anyMatch(Character::isISOControl))throw ApiError.bad("INVALID_FILE_TYPE");
        String fingerprint=hash(input.filename()+"\n"+input.bytes()+"\n"+input.sha256());
        // Reserve one durable session before performing remote I/O; actor/tenant/video lock serializes duplicate keys.
        String[] reservation=tx.execute(s->{
            authorize(actor,tenant,video,true);
            String existing=db.query("SELECT id FROM upload_sessions WHERE actor_id=? AND video_id=? AND request_key=?",(r,n)->r.getString(1),actor,video,key).stream().findFirst().orElse(null);
            if(existing!=null){
                Session u=get(existing,true);if(!u.hash.equals(fingerprint))throw new ApiError(409,"IDEMPOTENCY_CONFLICT");
                if(u.status.equals("INIT"))throw new ApiError(409,"UPLOAD_INITIALIZING");
                return new String[]{existing,"existing"};
            }
            // A single active upload/job per video; replacement cannot race an older transcode.
            Integer active=db.queryForObject("SELECT COUNT(*) FROM upload_sessions WHERE video_id=? AND status IN ('INIT','OPEN','COMPLETING','ABORTING') AND expires_at>UTC_TIMESTAMP(6)",Integer.class,video);
            Integer jobs=db.queryForObject("SELECT COUNT(*) FROM jobs WHERE video_id=? AND state IN ('QUEUED','RUNNING','RETRY_WAIT')",Integer.class,video);
            if(active>0||jobs>0)throw new ApiError(409,"MEDIA_BUSY");
            String uid=id(),mid=id(),object="videos/"+video+"/original/"+mid+"/source";
            db.update("INSERT INTO media_versions(id,tenant_id,video_id,original_key,expected_bytes,full_sha256,status) VALUES(?,?,?,?,?,?,'UPLOADING')",mid,tenant,video,object,input.bytes(),input.sha256());
            db.update("INSERT INTO upload_sessions(id,tenant_id,video_id,actor_id,media_id,filename,request_key,request_hash,status,expires_at) VALUES(?,?,?,?,?,?,?,?,'INIT',UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)",uid,tenant,video,actor,mid,input.filename(),key,fingerprint);
            return new String[]{uid,"new"};
        });
        Session u=get(reservation[0],false);
        if(reservation[1].equals("existing"))return describe(u);
        String provider;
        try{provider=s3.createMultipartUpload(b->b.bucket(BUCKET).key(u.key).checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumType(ChecksumType.COMPOSITE).contentType("application/octet-stream")).uploadId();}
        catch(RuntimeException e){db.update("UPDATE upload_sessions SET status='FAILED' WHERE id=? AND status='INIT'",u.id);db.update("UPDATE media_versions SET status='FAILED' WHERE id=?",u.media);throw unavailable();}
        try{
            int changed=tx.execute(s->{own(actor,tenant,video,u.id,true);return db.update("UPDATE upload_sessions SET provider_upload_id=?,status='OPEN' WHERE id=? AND status='INIT'",provider,u.id);});
            if(changed!=1)throw new ApiError(409,"UPLOAD_STATE_CHANGED");
        }catch(RuntimeException e){try{s3.abortMultipartUpload(b->b.bucket(BUCKET).key(u.key).uploadId(provider));}catch(RuntimeException ignored){}throw e;}
        return describe(get(u.id,false));
    }
    static ApiError unavailable(){return new ApiError(503,"MEDIA_STORAGE_UNAVAILABLE");}
    private List<Part> parts(Session u){
        if(u.provider==null||!Set.of("OPEN","COMPLETING").contains(u.status))return List.of();
        Map<Integer,String> expected=new HashMap<>();
        db.query("SELECT part_number,checksum_sha256 FROM upload_parts WHERE upload_id=?",(org.springframework.jdbc.core.RowCallbackHandler)r->expected.put(r.getInt(1),r.getString(2)),u.id);
        try{
            List<Part> result=new ArrayList<>();
            for(var page:s3.listPartsPaginator(b->b.bucket(BUCKET).key(u.key).uploadId(u.provider))){
                for(var p:page.parts()){
                    String checksum=p.checksumSHA256(),declared=expected.get(p.partNumber());
                    // LocalStack releases may omit checksum metadata. Full-object SHA is independently checked by worker.
                    if(checksum==null&&dev)checksum=declared;
                    if(declared==null||!declared.equals(checksum))throw ApiError.bad("PART_CHECKSUM_MISMATCH");
                    result.add(new Part(p.partNumber(),p.eTag(),checksum,p.size()));
                }
            }
            return result.stream().sorted(Comparator.comparingInt(Part::partNumber)).toList();
        }catch(S3Exception e){if(u.status.equals("COMPLETING")&&e.statusCode()==404)return List.of();throw unavailable();}
    }
    private Upload describe(Session u){return new Upload(u.id,PART,u.status,parts(u),u.job);}
    Upload status(String actor,String tenant,String video,String id){available();return describe(own(actor,tenant,video,id,false));}
    Url sign(String actor,String tenant,String video,String id,Sign input){
        available();Session u=tx.execute(s->{
            Session x=own(actor,tenant,video,id,true);open(x);
            if(input.partNumber()>count(x.bytes))throw ApiError.bad("INVALID_PART_NUMBER");
            String prior=db.query("SELECT checksum_sha256 FROM upload_parts WHERE upload_id=? AND part_number=?",(r,n)->r.getString(1),id,input.partNumber()).stream().findFirst().orElse(null);
            if(prior!=null&&!prior.equals(input.checksum()))throw new ApiError(409,"PART_CHECKSUM_CHANGED");
            if(prior==null)db.update("INSERT INTO upload_parts VALUES(?,?,?)",id,input.partNumber(),input.checksum());
            return x;
        });
        var request=UploadPartRequest.builder().bucket(BUCKET).key(u.key).uploadId(u.provider).partNumber(input.partNumber()).checksumSHA256(input.checksum()).contentLength(size(u.bytes,input.partNumber())).build();
        return new Url(signer.presignUploadPart(b->b.signatureDuration(Duration.ofMinutes(15)).uploadPartRequest(request)).url().toString());
    }
    JobResult complete(String actor,String tenant,String video,String id,String trace){
        available();
        Session u=own(actor,tenant,video,id,false);
        if(u.job!=null)return new JobResult(u.job);
        if(u.expired||!Set.of("OPEN","COMPLETING").contains(u.status))throw new ApiError(409,"UPLOAD_NOT_OPEN");
        // Remote complete outside DB locks. A lost response is recoverable by HEAD of this immutable object key.
        if(u.status.equals("OPEN")){
            List<Part> p=parts(u);
            if(p.size()!=count(u.bytes))throw ApiError.bad("MISSING_PARTS");
            for(int i=0;i<p.size();i++){Part part=p.get(i);if(part.partNumber()!=i+1||part.bytes()!=size(u.bytes,i+1))throw ApiError.bad("PART_SIZE_MISMATCH");}
            tx.executeWithoutResult(s->{Session locked=own(actor,tenant,video,id,true);open(locked);db.update("UPDATE upload_sessions SET status='COMPLETING' WHERE id=?",id);});
            try{
                List<CompletedPart> done=p.stream().map(v->CompletedPart.builder().partNumber(v.partNumber()).eTag(v.etag()).checksumSHA256(v.checksum()).build()).toList();
                s3.completeMultipartUpload(b->b.bucket(BUCKET).key(u.key).uploadId(u.provider).checksumType(ChecksumType.COMPOSITE).multipartUpload(m->m.parts(done)));
            }catch(RuntimeException e){/* HEAD below distinguishes a lost success response from an incomplete object. */}
        }
        HeadObjectResponse head;
        try{head=s3.headObject(b->b.bucket(BUCKET).key(u.key).checksumMode(ChecksumMode.ENABLED));}
        catch(S3Exception e){
            // If provider still has the upload, allow a retry of completion; otherwise keep recoverable COMPLETING.
            if(e.statusCode()==404){try{s3.listParts(b->b.bucket(BUCKET).key(u.key).uploadId(u.provider).maxParts(1));db.update("UPDATE upload_sessions SET status='OPEN' WHERE id=? AND status='COMPLETING'",id);}catch(RuntimeException ignored){}}
            throw unavailable();
        }
        if(head.contentLength()!=u.bytes||head.eTag()==null)throw ApiError.bad("OBJECT_SIZE_MISMATCH");
        List<String> checksums=db.query("SELECT checksum_sha256 FROM upload_parts WHERE upload_id=? ORDER BY part_number",(r,n)->r.getString(1),id);
        if(checksums.size()!=count(u.bytes))throw ApiError.bad("MISSING_PARTS");
        byte[] concatenated=new byte[32*checksums.size()];
        for(int i=0;i<checksums.size();i++)System.arraycopy(Base64.getDecoder().decode(checksums.get(i)),0,concatenated,i*32,32);
        String expected=Base64.getEncoder().encodeToString(digest(concatenated));
        if(head.checksumSHA256()!=null&&!head.checksumSHA256().equals(expected+"-"+checksums.size())&&!head.checksumSHA256().equals(expected))throw ApiError.bad("OBJECT_CHECKSUM_MISMATCH");
        if(!dev&&head.checksumSHA256()==null)throw ApiError.bad("OBJECT_CHECKSUM_MISSING");
        return tx.execute(s->{
            Session locked=own(actor,tenant,video,id,true);
            if(locked.job!=null)return new JobResult(locked.job);
            if(!locked.status.equals("COMPLETING"))throw new ApiError(409,"UPLOAD_STATE_CHANGED");
            String job=id();
            db.update("INSERT INTO jobs(id,tenant_id,video_id,media_version_id,state,deadline) VALUES(?,?,?,?,'QUEUED',UTC_TIMESTAMP(6)+INTERVAL 2 HOUR)",job,tenant,video,u.media);
            db.update("UPDATE upload_sessions SET status='UPLOADED',job_id=? WHERE id=?",job,id);
            db.update("UPDATE media_versions SET status='QUEUED' WHERE id=?",u.media);
            enqueue(job,tenant,1,trace);return new JobResult(job);
        });
    }
    void enqueue(String job,String tenant,long version,String trace){
        db.update("INSERT INTO outbox_events(event_id,tenant_id,aggregate_id,aggregate_version,event_type,payload) VALUES(?,?,?,?,'media.transcode.requested',JSON_OBJECT('jobId',?,'traceId',?))",
            id(),tenant,job,version,job,trace);
    }
    void abort(String actor,String tenant,String video,String id){
        available();Session u=tx.execute(s->{Session x=own(actor,tenant,video,id,true);if(x.status.equals("ABORTED"))return x;
            if(!Set.of("OPEN","ABORTING").contains(x.status))throw new ApiError(409,"UPLOAD_NOT_OPEN");
            db.update("UPDATE upload_sessions SET status='ABORTING' WHERE id=?",id);return x;});
        if(u.status.equals("ABORTED"))return;
        try{s3.abortMultipartUpload(b->b.bucket(BUCKET).key(u.key).uploadId(u.provider));}catch(S3Exception e){if(e.statusCode()!=404)throw unavailable();}
        tx.executeWithoutResult(s->{db.update("UPDATE upload_sessions SET status='ABORTED' WHERE id=? AND status='ABORTING'",id);db.update("UPDATE media_versions SET status='FAILED' WHERE id=? AND status='UPLOADING'",u.media);});
    }
    State state(String actor,String tenant,String video){
        available();authorize(actor,tenant,video,false);
        return db.query("""
            SELECT COALESCE(j.state,u.status),j.id,j.error_code,m.duration_ms,m.id FROM media_versions m
            JOIN upload_sessions u ON u.media_id=m.id LEFT JOIN jobs j ON j.media_version_id=m.id
            WHERE m.video_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT 1
            """,(r,n)->new State(r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Long.class),r.getString(5)),video).stream().findFirst().orElse(new State("NONE",null,null,null,null));
    }
    JobResult retry(String actor,String tenant,String video,String trace){
        available();return tx.execute(s->{
            authorize(actor,tenant,video,true);
            String job=db.query("SELECT id FROM jobs WHERE video_id=? ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE",(r,n)->r.getString(1),video).stream().findFirst().orElseThrow(ApiError::missing);
            if(db.update("UPDATE jobs SET state='QUEUED',deadline=UTC_TIMESTAMP(6)+INTERVAL 2 HOUR,lease_until=NULL,error_code=NULL,version=version+1 WHERE id=? AND state='FAILED' AND attempt<5",job)!=1)throw new ApiError(409,"JOB_NOT_RETRYABLE");
            long version=db.queryForObject("SELECT version FROM jobs WHERE id=?",Long.class,job);
            db.update("UPDATE media_versions m JOIN jobs j ON j.media_version_id=m.id SET m.status='QUEUED' WHERE j.id=?",job);enqueue(job,tenant,version,trace);return new JobResult(job);
        });
    }
}
