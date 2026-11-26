package io.dockvue.core.content;

import static io.dockvue.core.content.ContentModels.*;
import io.dockvue.core.identity.ApiError;
import io.dockvue.core.identity.TenantService;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContentService {
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;
    private final TenantService tenants;
    private final CursorCodec cursors;
    @PersistenceContext private EntityManager em;
    private static final String SELECT="""
        SELECT v.id,v.tenant_id,t.name,v.created_by,v.title,v.description,v.visibility,v.difficulty,
               v.duration_ms,v.status,v.preview_enabled,v.version,v.updated_at,v.catalog_at,
               (SELECT COUNT(*) FROM phases p WHERE p.video_id=v.id) AS phase_count
        FROM videos v JOIN tenants t ON t.id=v.tenant_id
        """;
    record Row(String id,String tenant,String channel,String owner,String title,String description,
               Visibility visibility,Difficulty difficulty,Long duration,String status,boolean preview,long version,
               LocalDateTime updated,LocalDateTime catalog,int count) {}
    record Label(String video,String kind,String name) {}
    record Prior(String hash,String id) {}
    private final RowMapper<Row> mapper=(r,n)->new Row(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
        r.getString(5),r.getString(6),Visibility.valueOf(r.getString(7)),Difficulty.valueOf(r.getString(8)),
        r.getObject(9,Long.class),r.getString(10),r.getBoolean(11),r.getLong(12),
        r.getObject(13,LocalDateTime.class),r.getObject(14,LocalDateTime.class),r.getInt(15));
    public ContentService(JdbcTemplate db,TenantService tenants,CursorCodec cursors){
        this.db=db;this.named=new NamedParameterJdbcTemplate(db);this.tenants=tenants;this.cursors=cursors;
    }
    private static LocalDateTime now(){return LocalDateTime.now(Clock.systemUTC()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static String id(){return UUID.randomUUID().toString();}
    private static String hash(String s){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static List<String> labels(List<String> source){
        TreeSet<String> normalized=new TreeSet<>();
        for(String s:source){
            String n=s.strip().toLowerCase(Locale.ROOT);
            if(n.isEmpty()||n.length()>40||n.chars().anyMatch(Character::isISOControl))throw ApiError.bad("INVALID_LABEL");
            normalized.add(n);
        }
        return List.copyOf(normalized);
    }
    private static Metadata normalize(Metadata m){
        return new Metadata(m.title().strip(),m.description().strip(),m.visibility(),m.difficulty(),m.durationMs(),labels(m.tags()),labels(m.stack()));
    }
    private static String fingerprint(Metadata m){
        List<String> parts=new ArrayList<>(List.of(m.title(),m.description(),m.visibility().name(),m.difficulty().name(),String.valueOf(m.durationMs()),String.valueOf(m.tags().size())));
        parts.addAll(m.tags());parts.addAll(m.stack());
        return hash(parts.stream().map(x->x.length()+":"+x).reduce("",String::concat));
    }
    private void audit(String actor,String tenant,String action,String target,String trace){
        db.update("INSERT INTO audit_events(id,tenant_id,actor_id,action,target_type,target_id,trace_id,sanitized_details) VALUES(?,?,?,?,'VIDEO',?,?,JSON_OBJECT())",
            id(),tenant,actor,action,target,trace);
    }
    private void setLabels(String video,Metadata m){
        db.update("DELETE FROM video_labels WHERE video_id=?",video);
        List<Object[]> rows=new ArrayList<>();
        for(String label:m.tags())rows.add(new Object[]{video,"TAG",label});
        for(String label:m.stack())rows.add(new Object[]{video,"STACK",label});
        if(!rows.isEmpty())db.batchUpdate("INSERT INTO video_labels(video_id,kind,label) VALUES(?,?,?)",rows);
    }
    // One bounded batch query, independent of result count. No JPA lazy collection or per-card round trips.
    private List<Summary> summaries(List<Row> rows){
        if(rows.isEmpty())return List.of();
        Map<String,List<Label>> map=new HashMap<>();
        for(Label l:named.query("SELECT video_id,kind,label FROM video_labels WHERE video_id IN (:ids) ORDER BY kind,label",
            Map.of("ids",rows.stream().map(Row::id).toList()),(r,n)->new Label(r.getString(1),r.getString(2),r.getString(3))))
            map.computeIfAbsent(l.video(),x->new ArrayList<>()).add(l);
        return rows.stream().map(r->{
            List<Label> ls=map.getOrDefault(r.id(),List.of());
            return new Summary(r.id(),r.tenant(),r.channel(),r.title(),r.description(),r.visibility(),r.difficulty(),r.duration(),
                ls.stream().filter(l->l.kind().equals("TAG")).map(Label::name).toList(),
                ls.stream().filter(l->l.kind().equals("STACK")).map(Label::name).toList(),
                r.status(),r.preview(),r.version(),r.updated().toInstant(ZoneOffset.UTC).toString(),r.count());
        }).toList();
    }
    private Row row(String video){
        return db.query(SELECT+" WHERE v.id=? AND v.status<>'ARCHIVED' AND t.status='ACTIVE'",mapper,video).stream().findFirst().orElseThrow(ApiError::missing);
    }
    private List<Phase> phases(String video){
        return db.query("SELECT id,ordinal,start_ms,end_ms,title,objective,prerequisites,estimated_minutes,start_state,completion_criteria,version FROM phases WHERE video_id=? ORDER BY ordinal",
            (r,n)->new Phase(r.getString(1),r.getInt(2),r.getLong(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getInt(8),r.getString(9),r.getString(10),r.getLong(11)),video);
    }
    private Detail detail(Row r){return new Detail(summaries(List.of(r)).getFirst(),phases(r.id()));}
    private void authorizeOwner(Row r,String actor,String tenant,boolean admin){
        if(!r.tenant().equals(tenant)||(!admin&&!r.owner().equals(actor)))throw ApiError.missing();
    }
    private Video writeVideo(String actor,String tenant,String video,long expected){
        boolean admin=tenants.lockCreator(actor,tenant);
        Row r=row(video);authorizeOwner(r,actor,tenant,admin);
        Video v=em.find(Video.class,video,LockModeType.PESSIMISTIC_WRITE);
        if(v==null||!v.status.equals("DRAFT"))throw new ApiError(409,"NOT_DRAFT");
        if(v.version!=expected)throw new ApiError(412,"STALE_VERSION");
        return v;
    }
    private void touch(Video v){v.timelineRevision++;v.updatedAt=now();em.flush();}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail create(String actor,Create request,String key,String trace){
        String tenant=request.tenantId().toString(); boolean admin=tenants.lockCreator(actor,tenant);
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw ApiError.bad("INVALID_IDEMPOTENCY_KEY");
        Metadata m=normalize(request.metadata());String h=fingerprint(m);
        db.update("DELETE FROM idempotency_keys WHERE actor_id=? AND tenant_scope=? AND route='createVideo' AND request_key=? AND expires_at<=UTC_TIMESTAMP(6)",actor,tenant,key);
        Prior prior=db.query("SELECT request_hash,JSON_UNQUOTE(JSON_EXTRACT(response_json,'$.id')) FROM idempotency_keys WHERE actor_id=? AND tenant_scope=? AND route='createVideo' AND request_key=?",
            (r,n)->new Prior(r.getString(1),r.getString(2)),actor,tenant,key).stream().findFirst().orElse(null);
        if(prior!=null){
            if(!prior.hash().equals(h))throw new ApiError(409,"IDEMPOTENCY_CONFLICT");
            Row r=row(prior.id());authorizeOwner(r,actor,tenant,admin);return detail(r);
        }
        Video v=new Video();v.id=id();v.tenantId=tenant;v.createdBy=actor;v.status="DRAFT";v.createdAt=now();v.apply(m);
        em.persist(v);em.flush();
        db.update("INSERT INTO video_repositories(video_id,tenant_id,registry_name) VALUES(?,?,?)",v.id,tenant,"videos/"+tenant+"/"+v.id);
        setLabels(v.id,m);
        db.update("INSERT INTO idempotency_keys(actor_id,tenant_scope,route,request_key,request_hash,state,response_json,expires_at) VALUES(?,?,'createVideo',?,?,'DONE',JSON_OBJECT('id',?),UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)",actor,tenant,key,h,v.id);
        audit(actor,tenant,"VIDEO_CREATED",v.id,trace);return detail(row(v.id));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail managed(String actor,String tenant,String video){
        boolean admin=tenants.canManageAll(actor,tenant);
        Row r=row(video);authorizeOwner(r,actor,tenant,admin);return detail(r);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page managedList(String actor,String tenant,String cursor,int limit){
        boolean admin=tenants.canManageAll(actor,tenant);
        List<Object> args=new ArrayList<>(List.of(tenant));
        String where="v.tenant_id=? AND t.status='ACTIVE' AND v.status<>'ARCHIVED'";
        if(!admin){where+=" AND v.created_by=?";args.add(actor);}
        return page(where,args,"updated_at","manage|"+actor+"|"+tenant+"|"+admin,cursor,limit,false);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail update(String actor,String tenant,String video,long expected,Metadata request,String trace){
        Video v=writeVideo(actor,tenant,video,expected);Metadata m=normalize(request);
        Long actual=db.query("SELECT m.duration_ms FROM videos v JOIN media_versions m ON m.id=v.current_media_version_id WHERE v.id=? AND m.status='READY'",
            (r,n)->r.getLong(1),video).stream().findFirst().orElse(null);
        if(actual!=null&&!actual.equals(m.durationMs()))throw ApiError.bad("MEDIA_DURATION_IMMUTABLE");
        if(m.durationMs()!=null&&phases(video).stream().anyMatch(p->p.endMs()>m.durationMs()))throw ApiError.bad("DURATION_BEFORE_PHASE_END");
        v.apply(m);setLabels(video,m);touch(v);audit(actor,tenant,"VIDEO_UPDATED",video,trace);return detail(row(video));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail timeline(String actor,String tenant,String video,long expected,Timeline request,String trace){
        Video v=writeVideo(actor,tenant,video,expected);
        TimelineRules.validate(request.phases(),v.durationMs);
        Map<String,Phase> prior=new HashMap<>();for(Phase p:phases(video))prior.put(p.id(),p);
        for(PhaseInput p:request.phases())if(p.id()!=null&&!prior.containsKey(p.id().toString()))throw ApiError.bad("INVALID_PHASE_ID");
        Set<String> keep=new HashSet<>();
        for(PhaseInput p:request.phases())if(p.id()!=null)keep.add(p.id().toString());
        for(String old:prior.keySet())if(!keep.contains(old))db.update("DELETE FROM phases WHERE video_id=? AND id=?",video,old);
        // Two-pass ordinal move prevents uniqueness collisions during reorder.
        db.update("UPDATE phases SET ordinal=ordinal+20 WHERE video_id=?",video);
        int ordinal=0;
        for(PhaseInput p:request.phases()){
            ordinal++;
            Object[] values={ordinal,p.startMs(),p.endMs(),p.title().strip(),p.objective(),p.prerequisites(),p.estimatedMinutes(),p.startState(),p.completionCriteria()};
            if(p.id()==null){
                List<Object> args=new ArrayList<>(List.of(id(),tenant,video));args.addAll(Arrays.asList(values));
                db.update("INSERT INTO phases(id,tenant_id,video_id,ordinal,start_ms,end_ms,title,objective,prerequisites,estimated_minutes,start_state,completion_criteria) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",args.toArray());
            }else{
                List<Object> args=new ArrayList<>(Arrays.asList(values));args.add(p.id().toString());args.add(video);
                db.update("UPDATE phases SET ordinal=?,start_ms=?,end_ms=?,title=?,objective=?,prerequisites=?,estimated_minutes=?,start_state=?,completion_criteria=?,version=version+1 WHERE id=? AND video_id=?",args.toArray());
            }
        }
        if(request.phases().isEmpty()){v.previewEnabled=false;v.catalogAt=null;}
        touch(v);audit(actor,tenant,"TIMELINE_UPDATED",video,trace);return detail(row(video));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Detail preview(String actor,String tenant,String video,long expected,boolean enabled,String trace){
        Video v=writeVideo(actor,tenant,video,expected);
        if(enabled&&phases(video).isEmpty())throw ApiError.bad("PREVIEW_REQUIRES_PHASE");
        if(enabled&&!v.previewEnabled)v.catalogAt=now();
        if(!enabled)v.catalogAt=null;
        v.previewEnabled=enabled;touch(v);audit(actor,tenant,enabled?"PREVIEW_ENABLED":"PREVIEW_DISABLED",video,trace);return detail(row(video));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void archive(String actor,String tenant,String video,long expected,String trace){
        Video v=writeVideo(actor,tenant,video,expected);v.status="ARCHIVED";v.previewEnabled=false;v.catalogAt=null;
        touch(v);audit(actor,tenant,"VIDEO_ARCHIVED",video,trace);
    }
    private Page page(String where,List<Object> args,String order,String scope,String cursor,int limit,boolean catalog){
        if(limit<1||limit>100)throw ApiError.bad("INVALID_LIMIT");
        CursorCodec.Position pos=cursors.decode(cursor,scope);
        if(pos!=null){where+=" AND (v."+order+" < ? OR (v."+order+" = ? AND v.id < ?))";args.add(pos.time());args.add(pos.time());args.add(pos.id());}
        args.add(limit+1);
        List<Row> all=db.query(SELECT+" WHERE "+where+" ORDER BY v."+order+" DESC,v.id DESC LIMIT ?",mapper,args.toArray());
        List<Row> rows=all.subList(0,Math.min(limit,all.size()));
        String next=null;if(all.size()>limit){Row last=rows.getLast();next=cursors.encode(new CursorCodec.Position(catalog?last.catalog():last.updated(),last.id()),scope);}
        return new Page(summaries(rows),next);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page catalog(String q,String tag,String stack,Difficulty difficulty,String cursor,int limit){
        q=q==null?"":q.strip().toLowerCase(Locale.ROOT);
        tag=tag==null?"":tag.strip().toLowerCase(Locale.ROOT);stack=stack==null?"":stack.strip().toLowerCase(Locale.ROOT);
        if(q.length()>120||tag.length()>40||stack.length()>40)throw ApiError.bad("INVALID_FILTER");
        String where="v.preview_enabled=TRUE AND v.visibility='PUBLIC' AND v.status='DRAFT' AND t.status='ACTIVE' AND v.catalog_at IS NOT NULL";
        List<Object> args=new ArrayList<>();
        if(!q.isEmpty()){where+=" AND (LOCATE(?,LOWER(v.title))>0 OR LOCATE(?,LOWER(v.description))>0)";args.add(q);args.add(q);}
        if(!tag.isEmpty()){where+=" AND EXISTS(SELECT 1 FROM video_labels l WHERE l.video_id=v.id AND l.kind='TAG' AND l.label=?)";args.add(tag);}
        if(!stack.isEmpty()){where+=" AND EXISTS(SELECT 1 FROM video_labels l WHERE l.video_id=v.id AND l.kind='STACK' AND l.label=?)";args.add(stack);}
        if(difficulty!=null){where+=" AND v.difficulty=?";args.add(difficulty.name());}
        String scope="catalog|"+hash(q.length()+":"+q+tag.length()+":"+tag+stack.length()+":"+stack+String.valueOf(difficulty));
        return page(where,args,"catalog_at",scope,cursor,limit,true);
    }
    private Row visible(String actor,String video){
        Row r=row(video);
        if(!r.preview()||!r.status().equals("DRAFT"))throw ApiError.missing();
        if(r.visibility()==Visibility.PRIVATE){if(actor==null)throw ApiError.missing();tenants.requireMember(actor,r.tenant());}
        return r;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail view(String actor,String video){return detail(visible(actor,video));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Progress progress(String actor,String video){
        tenants.requireActive(actor);Row r=visible(actor,video);
        return db.query("SELECT phase_id,video_version FROM preview_progress WHERE user_id=? AND video_id=?",
            (rs,n)->new Progress(rs.getString(1),rs.getLong(2),rs.getLong(2)!=r.version()),actor,video).stream().findFirst().orElse(new Progress(null,r.version(),false));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Progress saveProgress(String actor,String video,ProgressInput input){
        tenants.requireActive(actor);
        // Lock video before reading current preview/version/phase membership.
        Video v=em.find(Video.class,video,LockModeType.PESSIMISTIC_READ);
        if(v==null)throw ApiError.missing();
        Row r=visible(actor,video);
        if(input.videoVersion()!=r.version())throw new ApiError(412,"STALE_VERSION");
        if(phases(video).stream().noneMatch(p->p.id().equals(input.phaseId().toString())))throw ApiError.bad("INVALID_PHASE_ID");
        db.update("INSERT INTO preview_progress(user_id,video_id,phase_id,video_version,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE phase_id=?,video_version=?,updated_at=UTC_TIMESTAMP(6)",
            actor,video,input.phaseId().toString(),r.version(),input.phaseId().toString(),r.version());
        return new Progress(input.phaseId().toString(),r.version(),false);
    }
}
