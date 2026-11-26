package io.dockvue.core.content;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name="videos")
class Video {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length=36) String id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="tenant_id",length=36,nullable=false) String tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name="created_by",length=36,nullable=false) String createdBy;
    @Column(length=120,nullable=false) String title;
    @Column(length=4000,nullable=false) String description;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length=16,nullable=false) ContentModels.Visibility visibility;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length=16,nullable=false) ContentModels.Difficulty difficulty;
    @Column(length=24,nullable=false) String status;
    Long durationMs;
    boolean previewEnabled;
    LocalDateTime catalogAt, createdAt, updatedAt;
    long timelineRevision;
    @Version long version;
    protected Video() {}
    void apply(ContentModels.Metadata m) {
        title=m.title(); description=m.description(); visibility=m.visibility();
        difficulty=m.difficulty(); durationMs=m.durationMs();
        updatedAt=LocalDateTime.now(java.time.Clock.systemUTC());
    }
}
