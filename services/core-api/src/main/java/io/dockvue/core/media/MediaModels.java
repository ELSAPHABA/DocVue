package io.dockvue.core.media;
import jakarta.validation.constraints.*;
import java.util.List;
public final class MediaModels {
    public record Start(@NotBlank @Size(max=255) String filename,@Min(1) @Max(5368709120L) long bytes,@Pattern(regexp="[0-9a-f]{64}") @NotNull String sha256){}
    public record Sign(@Min(1) @Max(320) int partNumber,@NotNull @Pattern(regexp="[A-Za-z0-9+/]{43}=") String checksum){}
    public record Part(int partNumber,String etag,String checksum,long bytes){}
    public record Upload(String id,long partSize,String status,List<Part> parts,String jobId){}
    public record Url(String url){}
    public record JobResult(String jobId){}
    public record State(String status,String jobId,String errorCode,Long durationMs,String mediaId){}
    public record Claim(String jobId,long fence,String originalKey,long expectedBytes,String expectedSha256,String outputPrefix){}
    public record Heartbeat(@Min(1) long fence){}
    public record Complete(@Min(1) long fence,@Min(1) @Max(14400000) long durationMs,@NotNull @Pattern(regexp="[0-9a-f]{64}") String sha256){}
    public record Fail(@Min(1) long fence,@NotNull @Pattern(regexp="[A-Z0-9_]{1,64}") String code,boolean retryable){}
    public record Playback(String url,long durationMs,String mediaId){}
    public record Progress(@Min(0) long positionMs,@NotNull java.util.UUID mediaId){}
}
