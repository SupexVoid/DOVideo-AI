package com.example.server.editor;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class SmartSelectionRow {
 private String id,goal,state,snapshotJson,resultJson,model,promptVersion,errorCode,errorMessage;
 private Long userId,mediaId,transcriptId;
 private int transcriptRevision,completedBatches,totalBatches;
 private long durationMs;
 private LocalDateTime createdAt,updatedAt;
}
