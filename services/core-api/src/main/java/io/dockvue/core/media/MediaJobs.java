package io.dockvue.core.media;

import static io.dockvue.core.media.MediaModels.*;
import io.dockvue.core.identity.ApiError;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.slf4j.LoggerFactory;

@Service
class MediaJobs {
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(MediaJobs.class);
    final MediaService media; final JdbcTemplate db; final SqsClient sqs;
    record Job(String id,String tenant,String video,String media,String original,long bytes,String sha,String state,int attempt,long fence,long version,String output,boolean valid,boolean overdue){}
    record Event(String id,String tenant,String aggregate,long version,String type,String trace,Instant occurred,int attempt){}
    MediaJobs(MediaService media,SqsClient sqs){this.media=media;this.db=media.db;this.sqs=sqs;}
    private Job job(String id,boolean lock){
        return db.query("""
            SELECT j.id,j.tenant_id,j.video_id,j.media_version_id,m.original_key,m.expected_bytes,m.full_sha256,j.state,j.attempt,j.fence,j.version,j.output_prefix,
            COALESCE(j.lease_until>UTC_TIMESTAMP(6),FALSE),j.deadline<=UTC_TIMESTAMP(6)
            FROM jobs j JOIN media_versions m ON m.id=j.media_version_id WHERE j.id=?
            """+(lock?" FOR UPDATE":""),(r,n)->new Job(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7),r.getString(8),r.getInt(9),r.getLong(10),r.getLong(11),r.getString(12),r.getBoolean(13),r.getBoolean(14)),id).stream().findFirst().orElseThrow(ApiError::missing);
    }
    private boolean terminal(Job j){return Set.of("SUCCEEDED","FAILED","CANCELLED").contains(j.state);}
    private void failTerminal(Job j,String code){
        db.update("UPDATE jobs SET state='FAILED',error_code=?,lease_until=NULL,version=version+1 WHERE id=?",code,j.id);
        db.update("UPDATE media_versions SET status='FAILED' WHERE id=?",j.media);
    }
    Claim claim(String id){
        media.available();
        return media.tx.execute(s->{
            Job j=job(id,true);
            if(terminal(j))return null;
            if(j.state.equals("RUNNING")&&j.valid)throw new ApiError(409,"JOB_CLAIMED");
            if(j.overdue||j.attempt>=5){failTerminal(j,"JOB_DEADLINE_OR_ATTEMPTS");return null;}
            long f=j.fence+1;
            String prefix="videos/"+j.video+"/hls/"+j.media+"/attempt-"+f+"/";
            db.update("UPDATE jobs SET state='RUNNING',attempt=attempt+1,fence=?,output_prefix=?,lease_until=UTC_TIMESTAMP(6)+INTERVAL 120 SECOND,version=version+1,error_code=NULL WHERE id=?",f,prefix,j.id);
            db.update("UPDATE media_versions SET status='RUNNING' WHERE id=?",j.media);
            return new Claim(j.id,f,j.original,j.bytes,j.sha,prefix);
        });
    }
    private void requireFence(Job j,long fence){
        if(!j.state.equals("RUNNING")||j.fence!=fence||!j.valid||j.overdue)throw new ApiError(409,"STALE_JOB_FENCE");
    }
    void heartbeat(String id,long fence){
        media.tx.executeWithoutResult(s->{Job j=job(id,true);requireFence(j,fence);db.update("UPDATE jobs SET lease_until=LEAST(deadline,UTC_TIMESTAMP(6)+INTERVAL 120 SECOND) WHERE id=?",id);});
    }
    void complete(String id,Complete input){
        Job outside=job(id,false);
        if(outside.state.equals("SUCCEEDED")&&outside.fence==input.fence())return;
        requireFence(outside,input.fence());
        if(!outside.sha.equals(input.sha256())){fail(id,new Fail(input.fence(),"SOURCE_HASH_MISMATCH",false));return;}
        // Validate the master exists before the fenced DB pointer switch; no object I/O under locks.
        try{
            var h=media.s3.headObject(b->b.bucket(MediaService.BUCKET).key(outside.output+"master.m3u8"));
            if(h.contentLength()<1||h.contentLength()>1048576)throw ApiError.bad("INVALID_HLS_MASTER");
        }catch(S3Exception e){throw MediaService.unavailable();}
        media.tx.executeWithoutResult(s->{
            String state=db.query("SELECT status FROM videos WHERE id=? FOR UPDATE",(r,n)->r.getString(1),outside.video).stream().findFirst().orElseThrow(ApiError::missing);
            Job j=job(id,true);if(j.state.equals("SUCCEEDED")&&j.fence==input.fence())return;
            requireFence(j,input.fence());
            if(!state.equals("DRAFT")){failTerminal(j,"VIDEO_ARCHIVED");return;}
            Long end=db.queryForObject("SELECT COALESCE(MAX(end_ms),0) FROM phases WHERE video_id=?",Long.class,j.video);
            if(end>input.durationMs()){failTerminal(j,"PHASE_OUTSIDE_MEDIA");return;}
            db.update("UPDATE media_versions SET status='READY',duration_ms=?,hls_prefix=? WHERE id=?",input.durationMs(),j.output,j.media);
            db.update("UPDATE videos SET current_media_version_id=?,duration_ms=?,version=version+1,timeline_revision=timeline_revision+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?",j.media,input.durationMs(),j.video);
            db.update("UPDATE jobs SET state='SUCCEEDED',lease_until=NULL,error_code=NULL,version=version+1 WHERE id=?",id);
        });
    }
    void fail(String id,Fail input){
        media.tx.executeWithoutResult(s->{
            Job j=job(id,true);
            if(terminal(j)&&j.fence==input.fence())return;
            // Retries are recorded and re-dispatched transactionally, so an ACK loss is harmless.
            if(j.state.equals("RETRY_WAIT")&&j.fence==input.fence())return;
            requireFence(j,input.fence());
            if(!input.retryable()||j.attempt>=5||j.overdue){failTerminal(j,input.code());return;}
            retry(j,input.code());
        });
    }
    private void retry(Job j,String code){
        db.update("UPDATE jobs SET state='RETRY_WAIT',error_code=?,lease_until=NULL,version=version+1 WHERE id=?",code,j.id);
        db.update("UPDATE media_versions SET status='QUEUED' WHERE id=?",j.media);
        media.enqueue(j.id,j.tenant,j.version+1,MediaService.id());
        db.update("UPDATE outbox_events SET next_attempt_at=UTC_TIMESTAMP(6)+INTERVAL ? SECOND WHERE aggregate_id=? AND aggregate_version=?",
            Math.min(60,1<<Math.min(j.attempt,6))+new Random().nextInt(3),j.id,j.version+1);
    }
    @Scheduled(fixedDelay=2000)
    public void relay(){
        if(!media.enabled)return;
        try{
            List<Event> batch=media.tx.execute(s->{
                var rows=db.query("""
                    SELECT event_id,tenant_id,aggregate_id,aggregate_version,event_type,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceId')),occurred_at,attempt
                    FROM outbox_events WHERE next_attempt_at<=UTC_TIMESTAMP(6) AND (delivery_state='PENDING' OR (delivery_state='SENDING' AND lease_until<=UTC_TIMESTAMP(6)))
                    ORDER BY occurred_at LIMIT 10 FOR UPDATE SKIP LOCKED
                    """,(r,n)->new Event(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant(),r.getInt(8)+1));
                for(Event e:rows)db.update("UPDATE outbox_events SET delivery_state='SENDING',lease_until=UTC_TIMESTAMP(6)+INTERVAL 120 SECOND,attempt=? WHERE event_id=?",e.attempt,e.id);
                return rows;
            });
            if(batch.isEmpty())return;
            for(Event e:batch){
                try{
                    String queue=sqs.getQueueUrl(b->b.queueName("dockvue-media")).queueUrl();
                    String json=String.format(Locale.ROOT,"{\"eventId\":\"%s\",\"eventType\":\"%s\",\"schemaVersion\":1,\"aggregateId\":\"%s\",\"aggregateVersion\":%d,\"tenantId\":\"%s\",\"occurredAt\":\"%s\",\"traceId\":\"%s\",\"payload\":{\"jobId\":\"%s\"}}",
                        e.id,e.type,e.aggregate,e.version,e.tenant,e.occurred,e.trace,e.aggregate);
                    sqs.sendMessage(b->b.queueUrl(queue).messageBody(json));
                    db.update("UPDATE outbox_events SET delivery_state='SENT',lease_until=NULL WHERE event_id=? AND attempt=?",e.id,e.attempt);
                }catch(RuntimeException ex){db.update("UPDATE outbox_events SET delivery_state='PENDING',lease_until=NULL,next_attempt_at=UTC_TIMESTAMP(6)+INTERVAL 10 SECOND WHERE event_id=? AND attempt=?",e.id,e.attempt);LOG.warn("Media outbox delivery deferred");}
            }
        }catch(RuntimeException e){LOG.warn("Media outbox relay unavailable");}
    }
    @Scheduled(fixedDelay=30000)
    public void sweep(){
        if(!media.enabled)return;
        try{
            var ids=db.query("SELECT id FROM jobs WHERE (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)) OR (state IN ('QUEUED','RETRY_WAIT','RUNNING') AND deadline<=UTC_TIMESTAMP(6)) LIMIT 50",(r,n)->r.getString(1));
            for(String id:ids)media.tx.executeWithoutResult(s->{Job j=job(id,true);if(terminal(j))return;if(j.overdue||j.attempt>=5){failTerminal(j,"JOB_DEADLINE_OR_ATTEMPTS");return;}if(j.state.equals("RUNNING")&&!j.valid)retry(j,"LEASE_EXPIRED");});
            // Cleanup known expired provider uploads. Unknown orphan provider IDs require lifecycle rule (production).
            var expired=db.query("""
                SELECT u.id,m.original_key,u.provider_upload_id,u.media_id FROM upload_sessions u JOIN media_versions m ON m.id=u.media_id
                WHERE u.expires_at<=UTC_TIMESTAMP(6) AND u.status IN ('INIT','OPEN','ABORTING','COMPLETING') LIMIT 20
                """,(r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)});
            for(String[] x:expired){
                if(db.update("UPDATE upload_sessions SET status='ABORTING' WHERE id=? AND status IN ('INIT','OPEN','ABORTING','COMPLETING') AND expires_at<=UTC_TIMESTAMP(6)",x[0])!=1)continue;
                try{if(x[2]!=null)media.s3.abortMultipartUpload(b->b.bucket(MediaService.BUCKET).key(x[1]).uploadId(x[2]));}
                catch(S3Exception e){if(e.statusCode()!=404)continue;}
                media.tx.executeWithoutResult(s->{db.update("UPDATE upload_sessions SET status='EXPIRED' WHERE id=? AND status='ABORTING'",x[0]);db.update("UPDATE media_versions SET status='FAILED' WHERE id=? AND status='UPLOADING'",x[3]);});
            }
        }catch(RuntimeException e){LOG.warn("Media lease/expiry sweep deferred");}
    }
}
