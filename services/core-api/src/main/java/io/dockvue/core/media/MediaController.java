package io.dockvue.core.media;

import static io.dockvue.core.media.MediaModels.*;
import io.dockvue.core.identity.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class MediaController {
    final MediaService service;final PlaybackService playback;
    MediaController(MediaService service,PlaybackService playback){this.service=service;this.playback=playback;}
    static String actor(Jwt jwt){return jwt==null?null:jwt.getSubject();}
    static final String ROOT="/tenants/{tenantId}/videos/{videoId}";
    @PostMapping(ROOT+"/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    Upload start(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,@Valid @RequestBody Start input,
                 @RequestHeader(name="Idempotency-Key",required=false) String key){
        return service.start(actor(jwt),tenantId.toString(),videoId.toString(),input,key);
    }
    @GetMapping(ROOT+"/uploads/{uploadId}")
    Upload status(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,@PathVariable UUID uploadId){
        return service.status(actor(jwt),tenantId.toString(),videoId.toString(),uploadId.toString());
    }
    @PostMapping(ROOT+"/uploads/{uploadId}/parts")
    ResponseEntity<Url> sign(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,@PathVariable UUID uploadId,@Valid @RequestBody Sign input){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sign(actor(jwt),tenantId.toString(),videoId.toString(),uploadId.toString(),input));
    }
    @PostMapping(ROOT+"/uploads/{uploadId}/complete")
    JobResult complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,@PathVariable UUID uploadId,HttpServletRequest r){
        return service.complete(actor(jwt),tenantId.toString(),videoId.toString(),uploadId.toString(),ApiError.trace(r));
    }
    @DeleteMapping(ROOT+"/uploads/{uploadId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void abort(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,@PathVariable UUID uploadId){
        service.abort(actor(jwt),tenantId.toString(),videoId.toString(),uploadId.toString());
    }
    @GetMapping(ROOT+"/media")
    State state(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId){
        return service.state(actor(jwt),tenantId.toString(),videoId.toString());
    }
    @PostMapping(ROOT+"/media/retry")
    JobResult retry(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,HttpServletRequest r){
        return service.retry(actor(jwt),tenantId.toString(),videoId.toString(),ApiError.trace(r));
    }
    @GetMapping("/videos/{videoId}/playback")
    ResponseEntity<Playback> playback(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId){
        var result=playback.issue(actor(jwt),videoId.toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,result.cookie().toString()).body(result.response());
    }
    @GetMapping("/videos/{videoId}/stream/master.m3u8")
    ResponseEntity<?> master(@PathVariable UUID videoId,@CookieValue(name="DV_PLAYBACK",required=false) String cookie){
        return playback.stream(videoId.toString(),"master.m3u8",cookie);
    }
    @GetMapping("/videos/{videoId}/stream/{variant}/{file}")
    ResponseEntity<?> stream(@PathVariable UUID videoId,@PathVariable String variant,@PathVariable String file,@CookieValue(name="DV_PLAYBACK",required=false) String cookie){
        return playback.stream(videoId.toString(),variant+"/"+file,cookie);
    }
    @GetMapping("/videos/{videoId}/playback-progress")
    Progress progress(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId){
        return playback.progress(actor(jwt),videoId.toString());
    }
    @PutMapping("/videos/{videoId}/playback-progress")
    Progress progress(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId,@Valid @RequestBody Progress body){
        return playback.save(actor(jwt),videoId.toString(),body);
    }
}
