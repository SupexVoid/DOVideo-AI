package com.example.server.editor;
import com.example.server.editor.EditorModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SmartSelectionValidatorTest {
 final SmartSelectionValidator v=new SmartSelectionValidator(new ObjectMapper());
 final List<TimedCue> cues=List.of(new TimedCue("a",0,1000,"question"),new TimedCue("b",900,2000,"answer"));
 @Test void sortsDeduplicatesAndUsesMergedActualDuration(){var r=v.validate("{\"selections\":[{\"cueIds\":[\"b\",\"a\",\"b\"],\"reason\":\"context\"}]}",cues,2000);assertEquals(List.of("a","b"),r.keptCueIds());assertEquals(2000,r.durationMs());}
 @Test void emptyIsValid(){assertTrue(v.validate("{\"selections\":[]}",cues,2000).keptCueIds().isEmpty());}
 @Test void rejectsUnknownForeignIdsMalformedShapeAndOversizedReasons(){for(String raw:List.of("oops","{}","{\"selections\":[{\"cueIds\":[\"foreign\"],\"reason\":\"x\"}]}","{\"selections\":[{\"cueIds\":[\"a\"],\"reason\":\""+"x".repeat(1001)+"\"}]}","{\"selections\":[{\"cueIds\":[3],\"reason\":\"x\"}]}"))assertThrows(IllegalArgumentException.class,()->v.validate(raw,cues,2000));}
 @Test void batchesOverlapAndStayWithinBothLimits(){var many=new ArrayList<TimedCue>();for(int i=0;i<205;i++)many.add(new TimedCue("c"+i,i*10,i*10+10,"text"));var b=v.batches(many);assertEquals(3,b.size());assertEquals("c98",b.get(1).getFirst().id());assertEquals("c196",b.get(2).getFirst().id());assertTrue(b.stream().allMatch(x->x.size()<=100));}
 @Test void enforcesTextSingleCueAndTotalBatchBudget(){assertThrows(IllegalArgumentException.class,()->v.batches(List.of(new TimedCue("x",0,1,"x".repeat(12001)))));var many=new ArrayList<TimedCue>();for(int i=0;i<2000;i++)many.add(new TimedCue("c"+i,i,i+1,"text"));assertThrows(IllegalArgumentException.class,()->v.batches(many));var b=v.batches(List.of(new TimedCue("a",0,1,"x".repeat(7000)),new TimedCue("b",1,2,"x".repeat(7000))));assertEquals(2,b.size());}
}
