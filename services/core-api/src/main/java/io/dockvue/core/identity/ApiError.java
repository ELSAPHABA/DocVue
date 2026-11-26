package io.dockvue.core.identity;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.io.IOException;

public final class ApiError extends RuntimeException {
    final int status;
    final String code;
    ApiError(int status, String code) { super(code); this.status = status; this.code = code; }
    static ApiError bad(String code) { return new ApiError(422, code); }
    static ApiError missing() { return new ApiError(404, "NOT_FOUND"); }
    static ApiError forbidden() { return new ApiError(403, "FORBIDDEN"); }
    static ApiError unauthorized() { return new ApiError(401, "UNAUTHENTICATED"); }
    static String trace(HttpServletRequest r) {
        Object id = r.getAttribute("dockvue.trace");
        return id == null ? UUID.randomUUID().toString() : id.toString();
    }
    static ProblemDetail detail(int status, String code, HttpServletRequest r) {
        ProblemDetail p = ProblemDetail.forStatus(status);
        p.setTitle(code);
        p.setProperty("code", code);
        p.setProperty("traceId", trace(r));
        return p;
    }
    static void write(HttpServletRequest r, HttpServletResponse s, int status, String code) throws IOException {
        s.setStatus(status); s.setContentType("application/problem+json");
        s.getWriter().write("{\"type\":\"about:blank\",\"title\":\""+code+"\",\"status\":"+status+
            ",\"code\":\""+code+"\",\"traceId\":\""+trace(r)+"\"}");
    }
}

@RestControllerAdvice
class IdentityErrors {
    @ExceptionHandler(ApiError.class)
    ResponseEntity<ProblemDetail> error(ApiError e, HttpServletRequest r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.status == 429) b.header("Retry-After", "6");
        return b.body(ApiError.detail(e.status, e.code, r));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e, HttpServletRequest r) {
        int status = e instanceof HttpMessageNotReadableException ? 400 : 422;
        return ResponseEntity.status(status).body(ApiError.detail(status, "INVALID_INPUT", r));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(HttpServletRequest r) {
        return ResponseEntity.status(409).body(ApiError.detail(409, "CONFLICT", r));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> mismatch(HttpServletRequest r) {
        return ResponseEntity.badRequest().body(ApiError.detail(400, "INVALID_INPUT", r));
    }
}
