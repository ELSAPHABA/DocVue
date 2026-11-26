package io.dockvue.core.media;

import static io.dockvue.core.media.MediaModels.*;
import io.dockvue.core.identity.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import software.amazon.awssdk.services.s3.model.*;
import io.dockvue.core.media.MediaModels.Progress;

@Service
class PlaybackService {
    final MediaService media;
    final byte[] key;
    final boolean secure;
    record Ready(String id,String prefix,long duration,String tenant,String visibility){}
    record Ticket(String actor,String video,String media,long expires){}
    record Issued(Playback response,ResponseCookie cookie){}
    PlaybackService(MediaService media,@Value("${dockvue.media.playback-key:}") String configured,
                    @Value("${dockvue.auth.secure-cookie:true}") boolean secure){
        this.media=media;this.secure=secure;
        if(configured.isBlank()){key=new byte[32];new SecureRandom().nextBytes(key);}
        else {key=Base64.getDecoder().decode(configured);if(key.length<32)throw new IllegalStateException("Playback key must have 32 bytes");}
    }
    private Ready ready(String actor,String video){
        media.available();
        // Production CloudFront/OAC implementation is a separate deployment gate; never issue direct S3 production URLs.
        if(!media.dev)throw new ApiError(503,"CDN_NOT_CONFIGURED");
        if(actor!=null)media.tenants.requireActive(actor);
        Ready r=media.db.query("""
            SELECT m.id,m.hls_prefix,m.duration_ms,v.tenant_id,v.visibility
            FROM videos v JOIN tenants t ON t.id=v.tenant_id JOIN media_versions m ON m.id=v.current_media_version_id AND m.video_id=v.id
            WHERE v.id=? AND v.status='DRAFT' AND v.preview_enabled=TRUE AND t.status='ACTIVE' AND m.status='READY'
            """,(rs,n)->new Ready(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5)),video).stream().findFirst().orElseThrow(ApiError::missing);
        if(r.visibility.equals("PRIVATE")){if(actor==null)throw ApiError.missing();media.tenants.requireMember(actor,r.tenant);}
        return r;
    }
    private byte[] mac(String payload){
        try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(payload.getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static String enc(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String issue(String actor,String video,String mid){
        String payload=enc(((actor==null?"guest":actor)+"|"+video+"|"+mid+"|"+Instant.now().plusSeconds(600).getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        return payload+"."+enc(mac(payload));
    }
    private Ticket parse(String token,String video){
        try{
            if(token==null||token.length()>400)throw new IllegalArgumentException();
            String[] p=token.split("\\.",-1);
            if(p.length!=2||!MessageDigest.isEqual(mac(p[0]),Base64.getUrlDecoder().decode(p[1])))throw new IllegalArgumentException();
            String[] v=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8).split("\\|",-1);
            if(v.length!=4||!v[1].equals(video)||Long.parseLong(v[3])<=Instant.now().getEpochSecond())throw new IllegalArgumentException();
            return new Ticket(v[0].equals("guest")?null:UUID.fromString(v[0]).toString(),video,UUID.fromString(v[2]).toString(),Long.parseLong(v[3]));
        }catch(RuntimeException e){throw ApiError.missing();}
    }
    Issued issue(String actor,String video){
        Ready r=ready(actor,video);
        String root="/api/v1/videos/"+video+"/stream";
        ResponseCookie cookie=ResponseCookie.from("DV_PLAYBACK",issue(actor,video,r.id)).path(root).httpOnly(true).secure(secure).sameSite("Lax").maxAge(600).build();
        return new Issued(new Playback(root+"/master.m3u8",r.duration,r.id),cookie);
    }
    static boolean allowedPath(String path){return path.matches("master\\.m3u8|v[1-9][0-9]{0,3}/(index\\.m3u8|seg[0-9]{5}\\.ts)");}
    ResponseEntity<?> stream(String video,String path,String token){
        if(!allowedPath(path))throw ApiError.missing();
        Ticket t=parse(token,video);Ready r=ready(t.actor,video);
        if(!r.id.equals(t.media))throw ApiError.missing();
        String object=r.prefix+path;
        if(path.endsWith(".ts")){
            // Bytes go directly from local S3 emulator to the player. API sends only a short redirect.
            var url=media.signer.presignGetObject(b->b.signatureDuration(Duration.ofSeconds(Math.min(60,t.expires-Instant.now().getEpochSecond()))).getObjectRequest(g->g.bucket(MediaService.BUCKET).key(object)));
            return ResponseEntity.status(302).location(java.net.URI.create(url.url().toString())).cacheControl(CacheControl.noStore()).build();
        }
        try{
            var req=GetObjectRequest.builder().bucket(MediaService.BUCKET).key(object).build();
            try(var stream=media.s3.getObject(req)){
                if(stream.response().contentLength()>1048576)throw ApiError.bad("INVALID_HLS_PLAYLIST");
                byte[] body=stream.readNBytes(1048577);
                if(body.length>1048576)throw ApiError.bad("INVALID_HLS_PLAYLIST");
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl")).body(body);
            }
        }catch(java.io.IOException e){throw MediaService.unavailable();}catch(S3Exception e){if(e.statusCode()==404)throw ApiError.missing();throw MediaService.unavailable();}
    }
    Progress progress(String actor,String video){
        Ready r=ready(actor,video);media.tenants.requireActive(actor);
        Long position=media.db.query("SELECT position_ms FROM playback_progress WHERE user_id=? AND video_id=? AND media_version_id=?",(rs,n)->rs.getLong(1),actor,video,r.id).stream().findFirst().orElse(0L);
        return new Progress(position,UUID.fromString(r.id));
    }
    Progress save(String actor,String video,Progress p){
        Ready r=ready(actor,video);media.tenants.requireActive(actor);
        if(!r.id.equals(p.mediaId().toString()))throw new ApiError(409,"MEDIA_CHANGED");
        long position=Math.min(r.duration,p.positionMs());
        media.db.update("INSERT INTO playback_progress(user_id,video_id,media_version_id,position_ms) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE position_ms=?,updated_at=UTC_TIMESTAMP(6)",actor,video,r.id,position,position);
        return new Progress(position,p.mediaId());
    }
}
