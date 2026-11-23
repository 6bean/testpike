package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoVisualLocateRequest;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceVisionClient;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoVisualLocateServiceTest {

    @Autowired
    private AppautoVisualLocateService visualLocateService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @MockitoBean
    private ExecTaskService execTaskService;
    @MockitoBean
    private AiServiceVisionClient aiServiceVisionClient;

    @BeforeEach
    void switchesOn() {
        configCacheHolder.put(AppautoConfigReader.KEY_VISION_MASTER, "1");
        configCacheHolder.put(AppautoConfigReader.KEY_VISION_APPAUTO_LOCATE, "1");
    }

    @AfterEach
    void resetSwitches() {
        configCacheHolder.remove(AppautoConfigReader.KEY_VISION_MASTER);
        configCacheHolder.remove(AppautoConfigReader.KEY_VISION_APPAUTO_LOCATE);
    }

    
    private AppautoVisualLocateRequest request(Long taskId, Long forgedProjectId) {
        return new AppautoVisualLocateRequest(taskId, forgedProjectId, "aGk=", "image/png",
                List.of(Map.of("label", "提交", "x", 100, "y", 200)),
                Map.of("action", "app.tap", "selector", Map.of("testTag", "submit")),
                "app.tap", "tap");
    }

    private void stubTask(long projectId) {
        when(execTaskService.get(902L)).thenReturn(taskOf(projectId));
    }

    private ExecTaskInfo taskOf(long projectId) {
        return new ExecTaskInfo(902L, projectId, 1L, null, 2, null, null, "{}",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 1, null);
    }

    @Test
    void masterSwitchOffRejectsHumanWordsAndNeverForwards() {
        configCacheHolder.put(AppautoConfigReader.KEY_VISION_MASTER, "0");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(902L, null)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(aiServiceVisionClient);
    }

    @Test
    void locateSubSwitchOffRejectsHumanWordsAndNeverForwards() {
        configCacheHolder.put(AppautoConfigReader.KEY_VISION_APPAUTO_LOCATE, "0");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(902L, null)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(aiServiceVisionClient);
    }

    @Test
    void missingTaskIdRejectedAsValidationError() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        verifyNoInteractions(aiServiceVisionClient);
    }

    @Test
    void unknownTaskFailsClosedAsNotFoundWithZeroForward() {
        when(execTaskService.get(902L)).thenThrow(
                new ServiceException("执行任务不存在", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(902L, null)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(aiServiceVisionClient);
    }

    @Test
    void forwardsWithCoreResolvedProjectIdOverridingForgedBody() {
        stubTask(42L);
        when(aiServiceVisionClient.locate(any())).thenReturn(
                Map.of("x", 120, "y", 340, "label", "提交按钮", "confidence", 0.91));

        Map<String, Object> answer = visualLocateService.locate(request(902L, 999L));

        ArgumentCaptor<AppautoVisualLocateRequest> sent =
                ArgumentCaptor.forClass(AppautoVisualLocateRequest.class);
        verify(aiServiceVisionClient).locate(sent.capture());
        assertEquals(42L, sent.getValue().projectId());
        assertEquals(902L, sent.getValue().taskId());
        assertEquals("aGk=", sent.getValue().screenshotB64());
        assertEquals("app.tap", sent.getValue().action());
        assertEquals("tap", sent.getValue().verb());
        assertEquals(0.91, answer.get("confidence"));
        assertEquals(120, answer.get("x"));
    }
}
