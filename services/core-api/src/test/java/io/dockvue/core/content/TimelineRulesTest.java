package io.dockvue.core.content;
import io.dockvue.core.identity.ApiError;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
class TimelineRulesTest {
    private ContentModels.PhaseInput phase(UUID id,long start,long end){
        return new ContentModels.PhaseInput(id,start,end,"Phase","","",10,"","");
    }
    @Test void adjacentAndGappedIntervalsAreValid(){
        assertThatCode(()->TimelineRules.validate(List.of(phase(null,0,500),phase(null,500,1000),phase(null,1500,2000)),2000L)).doesNotThrowAnyException();
    }
    @Test void rejectsOverlapReverseAndDuration(){
        assertThatThrownBy(()->TimelineRules.validate(List.of(phase(null,0,500),phase(null,499,1000)),1000L)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->TimelineRules.validate(List.of(phase(null,10,10)),null)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->TimelineRules.validate(List.of(phase(null,0,1001)),1000L)).isInstanceOf(ApiError.class);
    }
    @Test void rejectsDuplicateIdsAndPhaseLimit(){
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->TimelineRules.validate(List.of(phase(id,0,1),phase(id,1,2)),null)).isInstanceOf(ApiError.class);
        var phases=java.util.stream.IntStream.range(0,21).mapToObj(i->phase(null,i,i+1)).toList();
        assertThatThrownBy(()->TimelineRules.validate(phases,null)).isInstanceOf(ApiError.class);
    }
    @Test void cursorIsSignedAndBoundToScope(){
        CursorCodec codec=new CursorCodec(true,"");
        var position=new CursorCodec.Position(LocalDateTime.parse("2026-11-26T01:02:03.123456"),UUID.randomUUID().toString());
        String token=codec.encode(position,"filter1");
        assertThat(codec.decode(token,"filter1")).isEqualTo(position);
        assertThatThrownBy(()->codec.decode(token+"x","filter1")).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->codec.decode(token,"filter2")).isInstanceOf(ApiError.class);
    }
}
