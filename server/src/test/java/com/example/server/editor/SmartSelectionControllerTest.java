package com.example.server.editor;
import com.example.server.controller.ApiExceptionHandler;
import com.example.server.service.AuthService;
import com.example.server.editor.SmartSelectionModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class SmartSelectionControllerTest {
 @Test void allThreeEndpointsUseResultEnvelopeAndAuthenticatedOwner()throws Exception{
  var service=mock(SmartSelectionService.class);var job=new Job("j",7L,8L,"keep","QUEUED",List.of(),List.of(),0,null,null,0,1);
  when(service.submit(new Request(7L,8L,"keep"),1L)).thenReturn(job);when(service.get("j",1L)).thenReturn(job);
  var mvc=MockMvcBuilders.standaloneSetup(new SmartSelectionController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
  mvc.perform(post("/media/editor/selections").requestAttr(AuthService.REQUEST_USER_ID,1L).contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":7,\"transcriptId\":8,\"goal\":\"keep\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("QUEUED"));
  mvc.perform(get("/media/editor/selections/j").requestAttr(AuthService.REQUEST_USER_ID,1L)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalBatches").value(1));
  mvc.perform(get("/media/editor/selections").param("mediaId","7").param("transcriptId","8").requestAttr(AuthService.REQUEST_USER_ID,1L)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
  verify(service).latest(7L,8L,1L);
 }
 @Test void executorRejectsFifthQueuedWorkAndDoesNotGrowThreads()throws Exception{
  var executor=(ThreadPoolExecutor)new SmartSelectionExecutorConfig().editorSmartSelectionExecutor();var started=new CountDownLatch(1);var stop=new CountDownLatch(1);
  try{executor.execute(()->{started.countDown();try{stop.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(2,TimeUnit.SECONDS));for(int i=0;i<4;i++)executor.execute(()->{});assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));assertEquals(1,executor.getMaximumPoolSize());}finally{stop.countDown();executor.shutdownNow();}
 }
}
