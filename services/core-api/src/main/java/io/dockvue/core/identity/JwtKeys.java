package io.dockvue.core.identity;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
class JwtKeys {
    private final RSAKey active;
    private final JWKSet publicKeys;
    private final NimbusJwtEncoder encoder;
    private final String issuer, audience;
    JwtKeys(@Value("${dockvue.auth.dev:false}") boolean dev,
            @Value("${dockvue.auth.private-key:}") String privatePath,
            @Value("${dockvue.auth.public-key:}") String publicPath,
            @Value("${dockvue.auth.previous-public-key:}") String previousPath,
            @Value("${dockvue.auth.issuer:dockvue}") String issuer,
            @Value("${dockvue.auth.audience:dockvue-api}") String audience) throws Exception {
        this.issuer=issuer; this.audience=audience;
        RSAPublicKey pub; RSAPrivateKey priv;
        if (privatePath.isBlank() && publicPath.isBlank() && dev) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair kp=generator.generateKeyPair();
            pub=(RSAPublicKey)kp.getPublic(); priv=(RSAPrivateKey)kp.getPrivate();
        } else {
            KeyFactory f=KeyFactory.getInstance("RSA");
            pub=(RSAPublicKey)f.generatePublic(new X509EncodedKeySpec(pem(publicPath,"PUBLIC KEY")));
            priv=(RSAPrivateKey)f.generatePrivate(new PKCS8EncodedKeySpec(pem(privatePath,"PRIVATE KEY")));
            if (!pub.getModulus().equals(priv.getModulus()) || pub.getModulus().bitLength()<2048)
                throw new IllegalStateException("Invalid RSA signing key pair");
        }
        active=key(pub,priv);
        List<JWK> keys=new ArrayList<>(); keys.add(active.toPublicJWK());
        if (!previousPath.isBlank()) {
            RSAPublicKey old=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(previousPath,"PUBLIC KEY")));
            if(old.getModulus().bitLength()<2048) throw new IllegalStateException("Invalid previous key");
            RSAKey k=key(old,null);
            if(!k.getKeyID().equals(active.getKeyID())) keys.add(k);
        }
        publicKeys=new JWKSet(keys);
        encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(active)));
    }
    private static byte[] pem(String path,String label) throws Exception {
        if(path.isBlank()) throw new IllegalStateException("RSA key paths required outside local development");
        return Base64.getMimeDecoder().decode(Files.readString(Path.of(path)).replace("-----BEGIN "+label+"-----","").replace("-----END "+label+"-----",""));
    }
    private static RSAKey key(RSAPublicKey pub,RSAPrivateKey priv) throws Exception {
        RSAKey.Builder b=new RSAKey.Builder(pub).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE);
        if(priv!=null) b.privateKey(priv);
        return b.keyID(new RSAKey.Builder(pub).build().computeThumbprint().toString()).build();
    }
    JwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor=new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,new ImmutableJWKSet<>(publicKeys)));
        NimbusJwtDecoder decoder=new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer),new JwtTimestampValidator(java.time.Duration.ZERO),
            jwt -> jwt.getAudience()!=null && jwt.getAudience().contains(audience) && jwt.getSubject()!=null && jwt.getSubject().matches("[0-9a-f-]{36}") && jwt.getExpiresAt()!=null && jwt.getId()!=null
              ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }
    String issue(String user) {
        Instant now=Instant.now();
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(user)
            .issuedAt(now).expiresAt(now.plusSeconds(600)).id(Secrets.id()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId(active.getKeyID()).build(),claims)).getTokenValue();
    }
    Map<String,Object> jwks() { return publicKeys.toJSONObject(); }
}
