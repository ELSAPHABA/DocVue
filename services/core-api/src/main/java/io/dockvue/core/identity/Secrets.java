package io.dockvue.core.identity;

import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Secrets() {}
    static String token() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String email(String s) {
        String e = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || !e.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,63}"))
            throw ApiError.bad("INVALID_EMAIL");
        return e;
    }
    static String opaque(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_-]{43}")) throw ApiError.unauthorized();
        return s;
    }
}
