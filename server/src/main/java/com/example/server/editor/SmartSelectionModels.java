package com.example.server.editor;
import java.util.List;
public final class SmartSelectionModels {
 private SmartSelectionModels(){}
 public record Request(Long mediaId,Long transcriptId,String goal){}
 public record Selection(List<String> cueIds,String reason){public Selection{cueIds=List.copyOf(cueIds);}}
 public record Recommendation(List<String> keptCueIds,List<Selection> selections,long durationMs){public Recommendation{keptCueIds=List.copyOf(keptCueIds);selections=List.copyOf(selections);}}
 public record Job(String id,Long mediaId,Long transcriptId,String goal,String state,List<String> keptCueIds,List<Selection> selections,long durationMs,String errorCode,String errorMessage,int completedBatches,int totalBatches){}
}
