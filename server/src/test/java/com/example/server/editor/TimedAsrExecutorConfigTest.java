package com.example.server.editor;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TimedAsrExecutorConfigTest {
    @Test void oneWorkerAndFourQueuedJobsRejectAdditionalWork() throws Exception {
        var executor=new TimedAsrExecutorConfig().editorTimedAsrExecutor();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            executor.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            for(int i=0;i<4;i++)executor.execute(()->{});
            assertThrows(RejectedExecutionException.class,()->executor.execute(()->{}));
        } finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
