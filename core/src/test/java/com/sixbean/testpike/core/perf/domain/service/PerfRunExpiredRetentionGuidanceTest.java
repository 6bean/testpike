package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R67 item (3): a deep link into a load-test run that the daily retention
 * sweep already cleared must answer the three-element guidance — the named
 * cause carrying the requested run id, the settings entry point spelled
 * literally, and the hot key name literally — instead of the old bare
 * not-found sentence. The guidance also states honestly that swept rows are
 * not restorable, so the user is never sent down a false path (R63).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunExpiredRetentionGuidanceTest {

    @Autowired
    private PerfRunService perfRunService;

    @Test
    void deepLinkAtUnknownRunAnswersRetentionGuidanceWithKeyAndPath() {
        long missingRunId = 9_876_543_210L;

        assertThatThrownBy(() -> perfRunService.view(9_876_543_200L, missingRunId))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode())
                        .isEqualTo(BizErrorCode.NOT_FOUND))
                .hasMessageContaining(String.valueOf(missingRunId))
                .hasMessageContaining("保留")
                .hasMessageContaining("perf.run.retention-days")
                .hasMessageContaining("系统设置")
                .hasMessageContaining("不可恢复");
    }
}
