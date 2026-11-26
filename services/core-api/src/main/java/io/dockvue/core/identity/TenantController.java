package io.dockvue.core.identity;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class TenantController {
    record Create(@NotBlank @Size(max=120) String name,@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{1,79}") String slug) {}
    record Invite(@NotBlank @Size(max=254) String email,@NotNull TenantService.Role role) {}
    record Code(@NotBlank @Size(max=128) String code) {}
    record Update(@NotNull TenantService.Role role,@NotNull @Pattern(regexp="ACTIVE|REVOKED") String status) {}
    record Approval(@NotNull Boolean approved) {}
    private final TenantService tenants;private final RateLimiter rate;
    TenantController(TenantService tenants,RateLimiter rate){this.tenants=tenants;this.rate=rate;}
    @GetMapping("/tenants") List<TenantService.Tenant> list(@AuthenticationPrincipal Jwt jwt){return tenants.list(jwt.getSubject());}
    @PostMapping("/tenants")
    ResponseEntity<TenantService.Tenant> create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Create body,@RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest r) {
        TenantService.Tenant t=tenants.create(jwt.getSubject(),body.name.strip(),body.slug,key,ApiError.trace(r));
        return ResponseEntity.status(201).eTag("\""+t.version()+"\"").body(t);
    }
    @GetMapping("/tenants/{tenantId}")
    ResponseEntity<TenantService.Tenant> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
        TenantService.Tenant t=tenants.get(jwt.getSubject(),tenantId.toString());
        return ResponseEntity.ok().eTag("\""+t.version()+"\"").body(t);
    }
    @GetMapping("/tenants/{tenantId}/members") List<TenantService.Member> members(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
        return tenants.members(jwt.getSubject(),tenantId.toString());
    }
    @GetMapping("/tenants/{tenantId}/creator-capability") @ResponseStatus(HttpStatus.NO_CONTENT)
    void creatorCapability(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId) {
        tenants.requireCreator(jwt.getSubject(),tenantId.toString());
    }
    @PostMapping("/tenants/{tenantId}/invitations") @ResponseStatus(HttpStatus.CREATED)
    TenantService.Invitation invite(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@Valid @RequestBody Invite body,HttpServletRequest r) {
        rate.take("invite",jwt.getSubject(),10);
        return tenants.invite(jwt.getSubject(),tenantId.toString(),Secrets.email(body.email),body.role,ApiError.trace(r));
    }
    @PostMapping("/invitations/accept")
    TenantService.Tenant accept(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Code body,HttpServletRequest r) {
        rate.take("invite-accept",jwt.getSubject(),10);return tenants.accept(jwt.getSubject(),body.code,ApiError.trace(r));
    }
    @PutMapping("/tenants/{tenantId}/members/{userId}")
    ResponseEntity<TenantService.Member> change(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenantId,@PathVariable UUID userId,@Valid @RequestBody Update body,@RequestHeader(name="If-Match",required=false) String tag,HttpServletRequest r) {
        long v=version(tag);
        TenantService.Member m=tenants.change(jwt.getSubject(),tenantId.toString(),userId.toString(),body.role,body.status,v,ApiError.trace(r));
        return ResponseEntity.ok().eTag("\""+m.version()+"\"").body(m);
    }
    @GetMapping("/admin/users/{userId}/creator-approval")
    ResponseEntity<TenantService.ApprovalState> approval(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID userId) {
        TenantService.ApprovalState s=tenants.approval(jwt.getSubject(),userId.toString());
        return ResponseEntity.ok().eTag("\""+s.version()+"\"").body(s);
    }
    @PutMapping("/admin/users/{userId}/creator-approval")
    ResponseEntity<Void> approve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID userId,@Valid @RequestBody Approval body,
        @RequestHeader(name="If-Match",required=false) String tag,HttpServletRequest r) {
        TenantService.ApprovalState s=tenants.approve(jwt.getSubject(),userId.toString(),body.approved,version(tag),ApiError.trace(r));
        return ResponseEntity.noContent().eTag("\""+s.version()+"\"").build();
    }
    private long version(String tag) {
        if(tag==null)throw new ApiError(428,"IF_MATCH_REQUIRED");
        if(!tag.matches("\"[0-9]{1,18}\""))throw new ApiError(400,"INVALID_ETAG");
        return Long.parseLong(tag.substring(1,tag.length()-1));
    }
}
