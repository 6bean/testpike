package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;

import java.util.List;
import java.util.Map;

/**
 * Shared L2 fixture for registering one executor with the standard web/app
 * descriptor (chromium, two slots, MOBILEWRIGHT engine family). Single source
 * per R40: command-queue suites that all need a pool member must not each
 * copy the same registration shape.
 */
public final class ExecutorFixtureRegistration {

    private ExecutorFixtureRegistration() {
    }

    /** Register one fixture executor and track its id for owner-scoped teardown. */
    public static long register(ExecutorService executorService, String name, List<Long> tracked) {
        long id = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"), "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
        tracked.add(id);
        return id;
    }
}
