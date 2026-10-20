package com.example.server.editor;
import com.example.server.editor.EditorModels.*;
import com.example.server.editor.SmartSelectionModels.*;
import com.example.server.service.AnalysisDispatchService;
import com.example.server.service.AgentExecutionBudget;
import com.example.server.service.AgentTelemetry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
@Service
public class SmartSelectionService implements SmartInitializingSingleton {
 private static final String PROMPT_VERSION="smart-selection-v1";
 private final EditorService editor;private final EditorStore store;private final SmartSelectionMapper jobs;
 private final SmartSelectionModel model;private final AnalysisDispatchService quota;private final ObjectMapper json;private final AgentTelemetry telemetry;
 private final Executor executor;private final TransactionTemplate committedWrite;private final String modelName;private final SmartSelectionValidator validator;
 public SmartSelectionService(EditorService editor,EditorStore store,SmartSelectionMapper jobs,SmartSelectionModel model,AnalysisDispatchService quota,AgentTelemetry telemetry,ObjectMapper json,
   @Qualifier("editorSmartSelectionExecutor") Executor executor,PlatformTransactionManager transactions,@Value("${ai.deepseek.model:deepseek-ai/DeepSeek-V3.2}") String modelName){
  this.editor=editor;this.store=store;this.jobs=jobs;this.model=model;this.quota=quota;this.telemetry=telemetry;this.json=json;this.executor=executor;this.modelName=modelName;validator=new SmartSelectionValidator(json);
  committedWrite=new TransactionTemplate(transactions);committedWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 @Override public void afterSingletonsInstantiated(){jobs.interrupt(LocalDateTime.now());}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Job submit(Request request,Long userId){
  if(request==null||request.goal()==null||request.goal().isBlank()||request.goal().length()>500)throw new IllegalArgumentException("请输入 1 到 500 字的选段目标");
  if(request.mediaId()==null||request.mediaId()<=0||request.transcriptId()==null||request.transcriptId()<=0)throw new IllegalArgumentException("视频或字幕 ID 无效");
  var doc=ownedTranscript(request.mediaId(),request.transcriptId(),userId);
  if(!"COMPLETED".equals(doc.state()))throw new IllegalArgumentException("字幕尚未完成");
  if(store.lockOwnedMedia(request.mediaId(),userId)==null)throw new SecurityException("无权访问视频");
  String goal=request.goal().trim();var previous=jobs.active(request.mediaId(),request.transcriptId(),userId,goal);
  if(previous!=null)return view(previous);
  new ClipRangePlanner().plan(doc.cues(),List.of(),doc.durationMs());
  var batches=validator.batches(doc.cues());quota.requireAiQuota(userId);
  var row=new SmartSelectionRow();row.setId(UUID.randomUUID().toString());row.setUserId(userId);row.setMediaId(doc.mediaId());row.setTranscriptId(doc.id());row.setTranscriptRevision(doc.revision());
  row.setGoal(goal);row.setState("QUEUED");row.setSnapshotJson(write(doc));row.setModel(modelName);row.setPromptVersion(PROMPT_VERSION);row.setTotalBatches(batches.size());row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(row.getCreatedAt());
  jobs.insert(row);dispatchAfterCommit(row);return view(row);
 }
 public Job get(String id,Long userId){
  if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("推荐任务 ID 无效");
  requireUser(userId);var row=jobs.find(id,userId);if(row==null)throw new NoSuchElementException("推荐任务不存在");
  if(!Objects.equals(row.getUserId(),userId))throw new SecurityException("无权访问推荐");
  ownedTranscript(row.getMediaId(),row.getTranscriptId(),userId);return view(row);
 }
 public Job latest(Long mediaId,Long transcriptId,Long userId){ownedTranscript(mediaId,transcriptId,userId);var row=jobs.latest(mediaId,transcriptId,userId);return row==null?null:view(row);}
 private TranscriptDocument ownedTranscript(Long mediaId,Long transcriptId,Long userId){
  requireUser(userId);if(mediaId==null||mediaId<=0||transcriptId==null||transcriptId<=0)throw new IllegalArgumentException("视频或字幕 ID 无效");
  var doc=editor.getTranscript(mediaId,transcriptId,userId);
  if(doc==null||!Objects.equals(doc.id(),transcriptId)||!Objects.equals(doc.mediaId(),mediaId))throw new SecurityException("字幕与视频不匹配");return doc;
 }
 private void requireUser(Long id){if(id==null||id<=0)throw new com.example.server.exception.BusinessException(com.example.server.common.ErrorCode.UNAUTHORIZED,"请先登录");}
 private void dispatchAfterCommit(SmartSelectionRow row){
  Runnable dispatch=()->{try{executor.execute(()->run(row));}catch(RejectedExecutionException full){committedWrite.executeWithoutResult(t->fail(row.getId(),"SELECTION_QUEUE_FULL","推荐队列繁忙，请稍后重新生成"));}};
  if(TransactionSynchronizationManager.isActualTransactionActive()){
   if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("推荐事务同步不可用");
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){dispatch.run();}});
  }else dispatch.run();
 }
 private void run(SmartSelectionRow row){
  String traceId=null;
  try{traceId=telemetry.start(row.getMediaId(),"smart-selection:"+row.getId());runTask(row);}
  finally{try{if(traceId!=null)telemetry.flush(traceId);}finally{telemetry.clear();}}
 }
 private void runTask(SmartSelectionRow row){
  Recommendation result;
  try(var budget=AgentExecutionBudget.open(8*60*1000)){
   if(jobs.start(row.getId(),LocalDateTime.now())!=1)return;
   // Verify continued ownership before disclosing snapshot content to the model.
   ownedTranscript(row.getMediaId(),row.getTranscriptId(),row.getUserId());
   TranscriptDocument snapshot=json.readValue(row.getSnapshotJson(),TranscriptDocument.class);
   var batches=validator.batches(snapshot.cues());List<Selection> merged=new ArrayList<>();int complete=0;
   for(var batch:batches){
    AgentExecutionBudget.check("智能选段");if(Thread.currentThread().isInterrupted())throw new InterruptedException();
    ownedTranscript(row.getMediaId(),row.getTranscriptId(),row.getUserId());
    String output=model.select(row.getGoal(),batch);AgentExecutionBudget.check("智能选段");
    Recommendation partial;
    try{partial=validator.validate(output,batch,snapshot.durationMs());}catch(IllegalArgumentException invalid){throw new InvalidOutput();}
    merged.addAll(partial.selections());
    if(jobs.progress(row.getId(),++complete,LocalDateTime.now())!=1)return;
   }
   ownedTranscript(row.getMediaId(),row.getTranscriptId(),row.getUserId());
   result=validator.merge(merged,snapshot.cues(),snapshot.durationMs());
   AgentExecutionBudget.check("智能选段");
  }catch(Exception error){
   String code=failureCode(error);
   if(error instanceof InterruptedException)Thread.currentThread().interrupt();fail(row.getId(),code,"推荐生成失败，请稍后重新生成");return;
  }
  try{jobs.complete(row.getId(),write(result),result.durationMs(),LocalDateTime.now());}
  catch(RuntimeException persistence){fail(row.getId(),"SELECTION_SAVE_FAILED","推荐保存失败，请重新生成");}
 }
 private String failureCode(Exception error){
  Throwable cause=error;
  for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause()){
   if(cause instanceof InvalidOutput)return "SELECTION_INVALID_OUTPUT";
   if(cause instanceof AgentExecutionBudget.DeadlineExceededException||cause instanceof TimeoutException)return "SELECTION_TIMEOUT";
   if(cause instanceof InterruptedException){Thread.currentThread().interrupt();return "SELECTION_INTERRUPTED";}
  }
  return "SELECTION_FAILED";
 }
 private static class InvalidOutput extends RuntimeException{}
 private void fail(String id,String code,String message){jobs.fail(id,code,message,LocalDateTime.now());}
 private Job view(SmartSelectionRow row){
  Recommendation result=new Recommendation(List.of(),List.of(),0);
  if("COMPLETED".equals(row.getState()))try{result=json.readValue(row.getResultJson(),Recommendation.class);}catch(Exception e){throw new IllegalStateException("推荐结果无法读取");}
  return new Job(row.getId(),row.getMediaId(),row.getTranscriptId(),row.getGoal(),row.getState(),result.keptCueIds(),result.selections(),result.durationMs(),row.getErrorCode(),row.getErrorMessage(),row.getCompletedBatches(),row.getTotalBatches());
 }
 private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("推荐数据无法保存");}}
}
