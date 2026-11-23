package com.sixbean.testpike.api;

import com.sixbean.testpike.common.config.GlobalExceptionHandler;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import com.sixbean.testpike.common.log.ShutdownLoggingPrewarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;


@SpringBootApplication
@Import({GlobalExceptionHandler.class, IdempotentGuard.class, ShutdownLoggingPrewarm.class})
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
