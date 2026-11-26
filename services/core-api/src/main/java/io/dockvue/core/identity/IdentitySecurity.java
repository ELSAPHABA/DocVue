package io.dockvue.core.identity;

import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class IdentitySecurity {
    @Bean PasswordEncoder passwordEncoder() { return new Argon2PasswordEncoder(16,32,1,32768,3); }
    @Bean JwtDecoder jwtDecoder(JwtKeys keys) { return keys.decoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http, RateLimiter limiter,
        @Value("${dockvue.auth.secure-cookie:true}") boolean secure,
        @Value("${dockvue.auth.dev:false}") boolean dev,
        @Value("${dockvue.auth.origins:}") String origins) throws Exception {
        Set<String> allowed=new HashSet<>(Arrays.asList(origins.split(",")));
        if(!dev && (!secure || allowed.isEmpty() || allowed.stream().anyMatch(o->!o.startsWith("https://"))))
            throw new IllegalStateException("Production auth requires HTTPS origins and Secure cookies");
        CookieCsrfTokenRepository csrf=new CookieCsrfTokenRepository();
        csrf.setCookieName("DV_CSRF"); csrf.setHeaderName("X-CSRF-TOKEN");
        csrf.setCookieCustomizer(b->b.path("/api/v1/auth").httpOnly(true).secure(secure).sameSite("Lax"));
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(r->r.getRequestURI().startsWith("/api/v1/auth/") && "POST".equals(r.getMethod())))
            .authorizeHttpRequests(a->a
                .requestMatchers("/actuator/health","/actuator/health/**","/actuator/info","/api/v1/status","/.well-known/jwks.json",
                    "/api/v1/auth/csrf","/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout",
                    "/api/v1/auth/resend-verification").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/videos","/api/v1/videos/*","/api/v1/videos/*/phases","/api/v1/videos/*/playback","/api/v1/videos/*/stream/**").permitAll()
                .requestMatchers("/internal/media/jobs/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->ApiError.write(r,s,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((r,s,x)->ApiError.write(r,s,403,"FORBIDDEN")))
            .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((r,s,x)->ApiError.write(r,s,401,"UNAUTHENTICATED")))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c) throws ServletException,IOException {
                    r.setAttribute("dockvue.trace",Secrets.id());
                    s.setHeader("X-Trace-Id",ApiError.trace(r));
                    if(r.getRequestURI().startsWith("/api/v1/auth/")) {
                        s.setHeader("Cache-Control","no-store");
                        if("POST".equals(r.getMethod()) && !allowed.contains(r.getHeader("Origin"))) {
                            ApiError.write(r,s,403,"INVALID_ORIGIN"); return;
                        }
                    }
                    c.doFilter(r,s);
                }
            }, SecurityContextHolderFilter.class)
            .addFilterAfter(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c) throws ServletException,IOException {
                    if(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
                        try { limiter.take("api-user",token.getName(),120); }
                        catch(ApiError e) { if(e.status==429)s.setHeader("Retry-After","1"); ApiError.write(r,s,e.status,e.code);return; }
                    }
                    c.doFilter(r,s);
                }
            }, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
