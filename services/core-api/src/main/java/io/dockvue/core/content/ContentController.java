package io.dockvue.core.content;

import static io.dockvue.core.content.ContentModels.*;
import io.dockvue.core.identity.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ContentController {
    final ContentService content;
    ContentController(ContentService content){this.content=content;}
    private static String actor(Jwt jwt){return jwt==null?null:jwt.getSubject();}
    private static long version(String tag){
        if(tag==null)throw new ApiError(428,"IF_MATCH_REQUIRED");
        if(!tag.matches("\"[0-9]{1,18}\""))throw new ApiError(400,"INVALID_ETAG");
        return Long.parseLong(tag.substring(1,tag.length()-1));
    }
    private static ResponseEntity<Detail> response(Detail d,int status){
        return ResponseEntity.status(status).eTag("\""+d.video().version()+"\"").cacheControl(CacheControl.noStore()).body(d);
    }
    @PostMapping("/videos")
    ResponseEntity<Detail> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Create body,
        @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request){
        return response(content.create(actor(jwt),body,key,ApiError.trace(request)),201);
    }
    @GetMapping("/tenants/{tenantId}/videos")
    Page manageList(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){
        return content.managedList(actor(jwt),tenantId.toString(),cursor,limit);
    }
    @GetMapping("/tenants/{tenantId}/videos/{videoId}")
    ResponseEntity<Detail> manage(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId){
        return response(content.managed(actor(jwt),tenantId.toString(),videoId.toString()),200);
    }
    @PutMapping("/tenants/{tenantId}/videos/{videoId}")
    ResponseEntity<Detail> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,
        @RequestHeader(name="If-Match",required=false) String tag,@Valid @RequestBody Metadata body,HttpServletRequest request){
        return response(content.update(actor(jwt),tenantId.toString(),videoId.toString(),version(tag),body,ApiError.trace(request)),200);
    }
    @PutMapping("/tenants/{tenantId}/videos/{videoId}/timeline")
    ResponseEntity<Detail> timeline(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,
        @RequestHeader(name="If-Match",required=false) String tag,@Valid @RequestBody Timeline body,HttpServletRequest request){
        return response(content.timeline(actor(jwt),tenantId.toString(),videoId.toString(),version(tag),body,ApiError.trace(request)),200);
    }
    @PutMapping("/tenants/{tenantId}/videos/{videoId}/preview")
    ResponseEntity<Detail> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,
        @RequestHeader(name="If-Match",required=false) String tag,@Valid @RequestBody Preview body,HttpServletRequest request){
        return response(content.preview(actor(jwt),tenantId.toString(),videoId.toString(),version(tag),body.enabled(),ApiError.trace(request)),200);
    }
    @DeleteMapping("/tenants/{tenantId}/videos/{videoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID videoId,
        @RequestHeader(name="If-Match",required=false) String tag,HttpServletRequest request){
        content.archive(actor(jwt),tenantId.toString(),videoId.toString(),version(tag),ApiError.trace(request));
    }
    @GetMapping("/videos")
    Page catalog(@RequestParam(required=false) String q,@RequestParam(required=false) String tag,@RequestParam(required=false) String stack,
        @RequestParam(required=false) Difficulty difficulty,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){
        return content.catalog(q,tag,stack,difficulty,cursor,limit);
    }
    @GetMapping("/videos/{videoId}")
    ResponseEntity<Detail> view(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId){return response(content.view(actor(jwt),videoId.toString()),200);}
    @GetMapping("/videos/{videoId}/phases")
    List<Phase> phases(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId){return content.view(actor(jwt),videoId.toString()).phases();}
    @GetMapping("/videos/{videoId}/preview-progress")
    Progress progress(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId){return content.progress(actor(jwt),videoId.toString());}
    @PutMapping("/videos/{videoId}/preview-progress")
    Progress progress(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID videoId,@Valid @RequestBody ProgressInput body,
        @RequestHeader(name="If-Match",required=false) String tag){
        if(version(tag)!=body.videoVersion())throw new ApiError(412,"STALE_VERSION");
        return content.saveProgress(actor(jwt),videoId.toString(),body);
    }
}
