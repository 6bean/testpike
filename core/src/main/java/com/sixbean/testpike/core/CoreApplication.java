package com.sixbean.testpike.core;

import com.sixbean.testpike.common.config.GlobalExceptionHandler;
import com.sixbean.testpike.common.idempotent.IdempotentAspect;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.lock.LockWatchdog;
import com.sixbean.testpike.common.lock.RedisReentrantLock;
import com.sixbean.testpike.common.log.ShutdownLoggingPrewarm;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mock.MockScenarioRules;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * testPike core service startup class
 *
 */
@SpringBootApplication
@Import({GlobalExceptionHandler.class, RedisReentrantLock.class, LockWatchdog.class, MockMatchEngine.class,
        MockScenarioRules.class,
        IdempotentAspect.class, MqIdempotentConsumeSupport.class, ShutdownLoggingPrewarm.class})
@org.springframework.scheduling.annotation.EnableScheduling
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
