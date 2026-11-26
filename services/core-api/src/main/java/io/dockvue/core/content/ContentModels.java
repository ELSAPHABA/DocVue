package io.dockvue.core.content;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ContentModels {
    public enum Visibility { PUBLIC, UNLISTED, PRIVATE }
    public enum Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
    public record Metadata(@NotBlank @Size(max=120) String title,
        @NotNull @Size(max=4000) String description, @NotNull Visibility visibility,
        @NotNull Difficulty difficulty, @Min(1) @Max(14400000) Long durationMs,
        @NotNull @Size(max=12) List<@NotBlank @Size(max=40) String> tags,
        @NotNull @Size(max=12) List<@NotBlank @Size(max=40) String> stack) {}
    public record Create(@NotNull java.util.UUID tenantId,@NotNull @Valid Metadata metadata) {}
    public record PhaseInput(java.util.UUID id,@Min(0) @Max(14400000) long startMs,
        @Min(1) @Max(14400000) long endMs,@NotBlank @Size(max=120) String title,
        @NotNull @Size(max=2000) String objective,@NotNull @Size(max=1000) String prerequisites,
        @Min(1) @Max(1440) int estimatedMinutes,@NotNull @Size(max=1000) String startState,
        @NotNull @Size(max=1000) String completionCriteria) {}
    public record Timeline(@NotNull @Size(max=20) List<@NotNull @Valid PhaseInput> phases) {}
    public record Preview(@NotNull Boolean enabled) {}
    public record ProgressInput(@NotNull java.util.UUID phaseId,@Min(0) long videoVersion) {}
    public record Phase(String id,int ordinal,long startMs,long endMs,String title,String objective,
        String prerequisites,int estimatedMinutes,String startState,String completionCriteria,long version) {}
    public record Summary(String id,String tenantId,String channel,String title,String description,
        Visibility visibility,Difficulty difficulty,Long durationMs,List<String> tags,List<String> stack,
        String status,boolean previewEnabled,long version,String updatedAt,int phaseCount) {}
    public record Detail(Summary video,List<Phase> phases) {}
    public record Page(List<Summary> items,String nextCursor) {}
    public record Progress(String phaseId,long videoVersion,boolean stale) {}
}
