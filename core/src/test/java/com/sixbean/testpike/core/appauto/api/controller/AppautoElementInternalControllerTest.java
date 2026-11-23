package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementCaptureRequest;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementCaptureService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Collaborative capture internal API contract (element library picture intake).
 * <p>Asserted surface: the endpoint is transport only — project anchor and crop
 * request travel to AppautoElementCaptureService untouched and the newly filed
 * element id travels back inside the standard Response envelope; the service's
 * human-readable rejections (missing live frame, crop outside the frame, scale
 * budget exceeded) pass through unmutated. Validation wording is single-sourced
 * in the service layer, the internal route must never add a second layer
 * (same posture as AppautoDeviceThumbnailInternalController).
 * <p>Service doubles here isolate the L2 wiring contract only; the real
 * capture chain runs on the live frame buffer in AppautoElementCaptureServiceTest.
 */
class AppautoElementInternalControllerTest {

    private final AppautoElementService elementService = mock(AppautoElementService.class);
    private final AppautoElementCaptureService captureService =
            mock(AppautoElementCaptureService.class);
    private final AppautoElementInternalController controller =
            new AppautoElementInternalController(elementService, captureService);

    private AppautoElementCaptureRequest request() {
        return new AppautoElementCaptureRequest(42L, 0, "login-button",
                10, 20, 100, 80, List.of(1.0, 0.8), 0.85);
    }

    @Test
    void capture_passesProjectAndRequestThrough_andReturnsFiledElementId() {
        AppautoElementCaptureRequest request = request();
        when(captureService.captureFromFrame(eq(7L), any())).thenReturn(55L);
        Long id = controller.capture(7L, request).getData();
        verify(captureService).captureFromFrame(7L, request);
        assertEquals(55L, id, "core-issued element id travels as the service computed it");
    }

    @Test
    void capture_serviceRejectionPassesThroughUnmutated() {
        ServiceException rejection =
                new ServiceException("所选区域超出画面范围", BizErrorCode.VALIDATION_ERROR);
        when(captureService.captureFromFrame(eq(7L), any(AppautoElementCaptureRequest.class)))
                .thenThrow(rejection);
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.capture(7L, request()));
        assertSame(rejection, thrown, "no second validation layer on the internal route");
    }
}
