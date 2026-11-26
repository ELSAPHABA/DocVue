package io.dockvue.core.content;
import io.dockvue.core.identity.ApiError;
import java.util.*;

final class TimelineRules {
    private TimelineRules(){}
    static void validate(List<ContentModels.PhaseInput> phases,Long duration) {
        if(phases.size()>20)throw ApiError.bad("PHASE_LIMIT");
        long previous=0;
        Set<UUID> ids=new HashSet<>();
        for(var p:phases){
            if(p.startMs()<previous||p.startMs()<0||p.endMs()<=p.startMs()||p.endMs()>14400000
                ||duration!=null&&p.endMs()>duration)throw ApiError.bad("INVALID_PHASE_RANGE");
            if(p.id()!=null&&!ids.add(p.id()))throw ApiError.bad("DUPLICATE_PHASE");
            previous=p.endMs();
        }
    }
}
