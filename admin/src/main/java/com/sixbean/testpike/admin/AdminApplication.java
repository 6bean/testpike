package com.sixbean.testpike.admin;

import com.sixbean.testpike.common.config.GlobalExceptionHandler;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import com.sixbean.testpike.common.log.ShutdownLoggingPrewarm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;


@SpringBootApplication
@Import({GlobalExceptionHandler.class, IdempotentGuard.class, ShutdownLoggingPrewarm.class})
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
