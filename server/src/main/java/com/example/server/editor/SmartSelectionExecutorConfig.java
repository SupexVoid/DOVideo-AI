package com.example.server.editor;
import org.springframework.context.annotation.*;
import java.util.concurrent.*;
@Configuration
public class SmartSelectionExecutorConfig {
 @Bean(name="editorSmartSelectionExecutor",destroyMethod="shutdownNow")
 public ExecutorService editorSmartSelectionExecutor(){return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),Thread.ofPlatform().name("editor-selection-",0).factory(),new ThreadPoolExecutor.AbortPolicy());}
}
