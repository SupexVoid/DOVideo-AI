package com.example.server.editor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ExportExecutorConfig {
    @Bean("editorExportExecutor")
    public ThreadPoolTaskExecutor editorExportExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("editor-export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
