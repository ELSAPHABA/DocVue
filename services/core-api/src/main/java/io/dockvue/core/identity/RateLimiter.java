package io.dockvue.core.identity;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class RateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        local cap=tonumber(ARGV[1])
        local rate=cap/60000
        local old=redis.call('HMGET',KEYS[1],'tokens','ts')
        local tokens=math.min(cap,(tonumber(old[1]) or cap)+math.max(0,now-(tonumber(old[2]) or now))*rate)
        local allowed=0
        if tokens>=1 then tokens=tokens-1; allowed=1 end
        redis.call('HSET',KEYS[1],'tokens',tokens,'ts',now)
        redis.call('PEXPIRE',KEYS[1],120000)
        return allowed
        """, Long.class);
    private final StringRedisTemplate redis;
    private final String env;
    RateLimiter(StringRedisTemplate redis, @Value("${dockvue.auth.namespace:local}") String env) { this.redis=redis; this.env=env; }
    void take(String scope, String subject, int capacity) {
        Long accepted;
        try { accepted=redis.execute(SCRIPT,List.of("dv:"+env+":rl:"+scope+":"+Secrets.hash(subject)),String.valueOf(capacity)); }
        catch (RuntimeException e) { throw new ApiError(503,"RATE_LIMIT_UNAVAILABLE"); }
        if (accepted == null) throw new ApiError(503,"RATE_LIMIT_UNAVAILABLE");
        if (accepted == 0L) throw new ApiError(429,"RATE_LIMITED");
    }
}
