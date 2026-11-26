package io.dockvue.core.media;

import static io.dockvue.core.media.MediaModels.*;
import io.dockvue.core.identity.ApiError;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/media/jobs/{jobId}")
class WorkerController {
    final MediaJobs jobs;final MediaService media;final byte[] secret;
    WorkerController(MediaJobs jobs,MediaService media,@Value("${dockvue.media.worker-token:}") String token){
        this.jobs=jobs;this.media=media;secret=token.getBytes(StandardCharsets.UTF_8);
        if(media.enabled&&secret.length<32)throw new IllegalStateException("Worker token must have at least 32 bytes");
    }
    private void check(String token){
        media.available();
        if(token==null||!MessageDigest.isEqual(secret,token.getBytes(StandardCharsets.UTF_8)))throw ApiError.unauthorized();
    }
    @PostMapping("/claim")
    ResponseEntity<Claim> claim(@PathVariable UUID jobId,@RequestHeader(name="X-Worker-Token",required=false) String token){
        check(token);Claim job=jobs.claim(jobId.toString());return job==null?ResponseEntity.noContent().build():ResponseEntity.ok(job);
    }
    @PostMapping("/heartbeat") @ResponseStatus(HttpStatus.NO_CONTENT)
    void heartbeat(@PathVariable UUID jobId,@RequestHeader(name="X-Worker-Token",required=false) String token,@Valid @RequestBody Heartbeat b){check(token);jobs.heartbeat(jobId.toString(),b.fence());}
    @PostMapping("/complete") @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(@PathVariable UUID jobId,@RequestHeader(name="X-Worker-Token",required=false) String token,@Valid @RequestBody Complete b){check(token);jobs.complete(jobId.toString(),b);}
    @PostMapping("/fail") @ResponseStatus(HttpStatus.NO_CONTENT)
    void fail(@PathVariable UUID jobId,@RequestHeader(name="X-Worker-Token",required=false) String token,@Valid @RequestBody Fail b){check(token);jobs.fail(jobId.toString(),b);}
}
