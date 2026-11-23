package com.sixbean.testpike.core.report.domain.component;

import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Component
public class ExportTaskExecutor {

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "export-task");
        t.setDaemon(true);
        return t;
    });

    
    public void submit(Runnable task) {
        pool.submit(task);
    }
}
