package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bidirectional fixtures for the single-source
 * timeout-family detector shared by the spec and judge group fan-outs:
 * every accepting shape must actually be recognised (a detector that only
 * matches one spelling silently un-trips the run signal), and the
 * non-timeout shapes must stay outside so the fail-closed vocabulary of
 * format failures is never folded into a timeout attribution.
 */
class LlmTimeoutsTest {

    @Test
    void aiTimeoutRefusalIsRecognised() {
        assertTrue(LlmTimeouts.isTimeoutFamily(
                new ServiceException("模型服务调用超时，本组未完成", BizErrorCode.AI_TIMEOUT)));
    }

    @Test
    void socketTimeoutDeepInCauseChainIsRecognised() {
        RuntimeException transportFailure = new RuntimeException("读超时",
                new IllegalStateException("外层包装", new SocketTimeoutException("read timed out")));
        assertTrue(LlmTimeouts.isTimeoutFamily(transportFailure));
    }

    @Test
    void aiTimeoutWrappedInAnotherFailureIsRecognised() {
        RuntimeException wrapped = new RuntimeException("扇出包装",
                new ServiceException("模型服务调用超时", BizErrorCode.AI_TIMEOUT));
        assertTrue(LlmTimeouts.isTimeoutFamily(wrapped));
    }

    @Test
    void formatErrorAndPlainFailuresStayOutsideTheFamily() {
        assertFalse(LlmTimeouts.isTimeoutFamily(new ServiceException("模型服务连接失败",
                BizErrorCode.AI_FORMAT_ERROR)));
        assertFalse(LlmTimeouts.isTimeoutFamily(new IllegalStateException("解析失败")));
        assertFalse(LlmTimeouts.isTimeoutFamily(null));
    }

    @Test
    void selfReferencingCauseCycleTerminatesAndStaysNegative() {
        RuntimeException cycle = new RuntimeException("自引用") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(LlmTimeouts.isTimeoutFamily(cycle));
    }
}
