package com.sixbean.testpike.aiservice;

import com.sixbean.testpike.common.config.GlobalExceptionHandler;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.log.ShutdownLoggingPrewarm;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
@Import({GlobalExceptionHandler.class, RedisReentrantLock.class,
        IdempotentAspect.class, MqIdempotentConsumeSupport.class, ShutdownLoggingPrewarm.class})
public class AiserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiserviceApplication.class, args);
    }
}
