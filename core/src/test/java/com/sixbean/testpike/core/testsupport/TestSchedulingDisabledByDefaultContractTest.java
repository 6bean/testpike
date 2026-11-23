package com.sixbean.testpike.core.testsupport;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.retention.domain.component.RetentionCleanupScheduler;
import com.sixbean.testpike.core.sched.domain.component.ScheduleTriggerScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract for the shared-scheduler test default.
 *
 * <p>Every {@code @Scheduled} component in core sits behind the class-level master gate
 * {@code testpike.scheduling.enabled} (matchIfMissing=true keeps production behaviour
 * untouched). Before this contract existed, plain {@code @SpringBootTest} contexts booted
 * with the gate unset, so scheduler tasks fired during L2 runs and contended the shared
 * database with long-running test transactions.
 *
 * <p>Expected end state: on the test classpath the gate resolves to "false" WITHOUT any
 * per-class opt-out, and representative scheduler beans are therefore absent from the
 * context. Tests that genuinely need a scheduler bean opt back in explicitly via
 * {@code @TestPropertySource(properties = "testpike.scheduling.enabled=true")}.
 */
@SpringBootTest(classes = CoreApplication.class)
class TestSchedulingDisabledByDefaultContractTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerMasterGateResolvesDisabledWithoutPerClassOptOut() {
        assertEquals("false", environment.getProperty("testpike.scheduling.enabled"));
    }

    @Test
    void representativeSchedulerBeansAreAbsentInDefaultTestContext() {
        assertEquals(0, context.getBeanNamesForType(ScheduleTriggerScanner.class).length);
        assertEquals(0, context.getBeanNamesForType(RetentionCleanupScheduler.class).length);
    }
}
