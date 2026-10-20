package com.example.server.editor;

import org.springframework.context.annotation.*;
import java.util.concurrent.*;

@Configuration
public class TimedAsrExecutorConfig {
    @Bean(name="editorTimedAsrExecutor",destroyMethod="shutdownNow")
    public ExecutorService editorTimedAsrExecutor() {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().name("editor-timed-asr-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
}
