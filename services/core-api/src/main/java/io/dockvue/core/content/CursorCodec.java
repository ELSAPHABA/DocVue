package io.dockvue.core.content;

import io.dockvue.core.identity.ApiError;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CursorCodec {
    record Position(LocalDateTime time,String id) {}
    private final byte[] key;
    CursorCodec(@Value("${dockvue.auth.dev:false}") boolean dev,@Value("${dockvue.catalog.cursor-key:}") String secret) {
        if(secret.isBlank()&&dev){key=new byte[32];new SecureRandom().nextBytes(key);}
        else {key=Base64.getDecoder().decode(secret);if(key.length<32)throw new IllegalStateException("Cursor key needs 32 bytes or more");}
    }
    private byte[] mac(String text) {
        try {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(text.getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    String encode(Position position,String scope) {
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString((position.time()+"|"+position.id()).getBytes(StandardCharsets.UTF_8));
        return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(scope+"|"+payload));
    }
    Position decode(String cursor,String scope) {
        if(cursor==null||cursor.isBlank())return null;
        try {
            if(cursor.length()>300)throw new IllegalArgumentException();
            String[] p=cursor.split("\\.",-1);
            if(p.length!=2||!MessageDigest.isEqual(mac(scope+"|"+p[0]),Base64.getUrlDecoder().decode(p[1])))throw new IllegalArgumentException();
            String[] value=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8).split("\\|",-1);
            if(value.length!=2)throw new IllegalArgumentException();
            return new Position(LocalDateTime.parse(value[0]),UUID.fromString(value[1]).toString());
        } catch(RuntimeException e){throw ApiError.bad("INVALID_CURSOR");}
    }
}
