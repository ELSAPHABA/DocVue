package io.dockvue.core.identity;

import java.nio.file.*;
import java.security.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

class IdentityCryptoTest {
    @TempDir Path temp;
    @Test void tokenHashAndEmailNormalization() {
        String token=Secrets.token();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Secrets.hash(token)).matches("[a-f0-9]{64}").isNotEqualTo(token);
        assertThat(Secrets.token()).isNotEqualTo(token);
        assertThat(Secrets.email(" Test@EXAMPLE.com ")).isEqualTo("test@example.com");
        assertThatThrownBy(()->Secrets.email("x")).isInstanceOf(ApiError.class);
    }
    @Test void argon2idUsesRandomSaltAndRejectsWrongPassword() {
        var encoder=new IdentitySecurity().passwordEncoder();
        String pass=Secrets.token();
        long start=System.nanoTime();
        String a=encoder.encode(pass),b=encoder.encode(pass);
        assertThat(a).startsWith("$argon2id$").isNotEqualTo(b);
        assertThat(encoder.matches(pass,a)).isTrue();
        assertThat(encoder.matches("wrong",a)).isFalse();
        System.out.printf("Argon2id smoke: two encodes + two checks, %d ms (not a production benchmark)%n",
            (System.nanoTime()-start)/1_000_000);
    }
    @Test void jwtAcceptsPreviousPublicKeyDuringRotationButRejectsUnknownKeyAndAudience() throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);
        KeyPair old=g.generateKeyPair(),next=g.generateKeyPair();
        Path op=pem("old-private.pem","PRIVATE KEY",old.getPrivate().getEncoded());
        Path ou=pem("old-public.pem","PUBLIC KEY",old.getPublic().getEncoded());
        Path np=pem("new-private.pem","PRIVATE KEY",next.getPrivate().getEncoded());
        Path nu=pem("new-public.pem","PUBLIC KEY",next.getPublic().getEncoded());
        JwtKeys before=new JwtKeys(false,op.toString(),ou.toString(),"","issuer","aud");
        JwtKeys during=new JwtKeys(false,np.toString(),nu.toString(),ou.toString(),"issuer","aud");
        JwtKeys after=new JwtKeys(false,np.toString(),nu.toString(),"","issuer","aud");
        String id=Secrets.id(),oldToken=before.issue(id),newToken=during.issue(id);
        assertThat(during.decoder().decode(oldToken).getSubject()).isEqualTo(id);
        assertThat(during.decoder().decode(newToken).getSubject()).isEqualTo(id);
        assertThatThrownBy(()->after.decoder().decode(oldToken)).isInstanceOf(JwtException.class);
        JwtKeys wrongAudience=new JwtKeys(false,np.toString(),nu.toString(),"","issuer","other");
        assertThatThrownBy(()->after.decoder().decode(wrongAudience.issue(id))).isInstanceOf(JwtException.class);
        var publicSet=com.nimbusds.jose.jwk.JWKSet.parse(during.jwks());
        assertThat(publicSet.getKeys()).hasSize(2).allSatisfy(k->assertThat(k.isPrivate()).isFalse());
        assertThatThrownBy(()->new JwtKeys(false,"","","","issuer","aud")).isInstanceOf(IllegalStateException.class);
    }
    private Path pem(String file,String label,byte[] bytes)throws Exception{
        Path path=temp.resolve(file);
        Files.writeString(path,"-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n");
        return path;
    }
}
