package io.dockvue.core.identity;

import java.time.*;
import java.util.Map;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class IdentityController {
    record Credentials(@NotBlank @Size(max=254) String email,@NotBlank @Size(min=12,max=128) String password) {}
    record Login(@NotBlank @Size(max=254) String email,@NotBlank @Size(max=128) String password) {}
    record Email(@NotBlank @Size(max=254) String email) {}
    record Code(@NotBlank @Size(max=128) String code) {}
    record Access(String accessToken,int expiresIn) {}
    record Profile(String id,String email,boolean emailVerified,boolean creatorApproved,String platformRole) {}
    private final IdentityService identity; private final JwtKeys jwt; private final RateLimiter rate; private final boolean secure;
    IdentityController(IdentityService identity,JwtKeys jwt,RateLimiter rate,@Value("${dockvue.auth.secure-cookie:true}") boolean secure) {
        this.identity=identity;this.jwt=jwt;this.rate=rate;this.secure=secure;
    }
    @GetMapping("/.well-known/jwks.json") Map<String,Object> jwks() { return jwt.jwks(); }
    @GetMapping("/api/v1/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    private void limit(String action,String email,HttpServletRequest r) {
        rate.take(action+"-ip",r.getRemoteAddr(),10);
        if(email!=null) rate.take(action+"-account",email,10);
    }
    @PostMapping("/api/v1/auth/register") @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody Credentials body,HttpServletRequest r) {
        String email=Secrets.email(body.email);limit("register",email,r);identity.register(email,body.password,ApiError.trace(r));
    }
    @PostMapping("/api/v1/auth/resend-verification") @ResponseStatus(HttpStatus.ACCEPTED)
    void resend(@Valid @RequestBody Email body,HttpServletRequest r) {
        String email=Secrets.email(body.email);limit("resend",email,r);identity.resend(email,ApiError.trace(r));
    }
    @PostMapping("/api/v1/auth/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(@AuthenticationPrincipal Jwt principal,@Valid @RequestBody Code body,HttpServletRequest r) {
        limit("verify",null,r);identity.verify(principal.getSubject(),body.code,ApiError.trace(r));
    }
    @PostMapping("/api/v1/auth/login")
    ResponseEntity<Access> login(@Valid @RequestBody Login body,HttpServletRequest r) {
        String email=Secrets.email(body.email);limit("login",email,r);
        IdentityService.Session session=identity.login(email,body.password,ApiError.trace(r));
        if(session==null)throw ApiError.unauthorized();
        return response(session);
    }
    @PostMapping("/api/v1/auth/refresh")
    ResponseEntity<?> refresh(@CookieValue(name="DV_REFRESH",required=false) String raw,HttpServletRequest r) {
        limit("refresh",null,r);
        IdentityService.Rotation result=identity.rotate(raw,ApiError.trace(r));
        if(result.failure()!=null) return ResponseEntity.status(401).header(HttpHeaders.SET_COOKIE,clear().toString())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(ApiError.detail(401,result.failure(),r));
        return response(result.session());
    }
    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name="DV_REFRESH",required=false) String raw,HttpServletRequest r) {
        identity.logout(raw,ApiError.trace(r));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,clear().toString()).build();
    }
    @GetMapping("/api/v1/me") Profile me(@AuthenticationPrincipal Jwt principal) {
        IdentityService.User u=identity.current(principal.getSubject());
        return new Profile(u.id(),u.email(),u.verified(),u.approved(),u.platformRole());
    }
    private ResponseCookie cookie(String value,long age) {
        return ResponseCookie.from("DV_REFRESH",value).httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth").maxAge(age).build();
    }
    private ResponseCookie clear() { return cookie("",0); }
    private ResponseEntity<Access> response(IdentityService.Session s) {
        long age=Math.max(0,Duration.between(Instant.now(),s.expiresAt()).toSeconds());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(s.refresh(),age).toString()).body(new Access(jwt.issue(s.userId()),600));
    }
}
