package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoVisualLocateRequest;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoVisualLocateService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceVisionClient;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.stereotype.Service;

import java.util.Map;


@Service
public class AppautoVisualLocateServiceImpl implements AppautoVisualLocateService {

    
    private static final String GATE_ON = "1";

    private final AppautoConfigReader configReader;
    private final ExecTaskService execTaskService;
    private final AiServiceVisionClient aiServiceVisionClient;

    public AppautoVisualLocateServiceImpl(AppautoConfigReader configReader,
                                          ExecTaskService execTaskService,
                                          AiServiceVisionClient aiServiceVisionClient) {
        this.configReader = configReader;
        this.execTaskService = execTaskService;
        this.aiServiceVisionClient = aiServiceVisionClient;
    }

    @Override
    public Map<String, Object> locate(AppautoVisualLocateRequest request) {
        if (request.taskId() == null) {
            throw new ServiceException("视觉定位请求缺少执行任务号，无法核对任务归属，已拒绝",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, String> gates = configReader.visualFallbackKeys();
        if (!GATE_ON.equals(gates.get(AppautoConfigReader.KEY_VISION_MASTER))
                || !GATE_ON.equals(gates.get(AppautoConfigReader.KEY_VISION_APPAUTO_LOCATE))) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("视觉定位兜底",
                    AppautoConfigReader.KEY_VISION_MASTER,
                    AppautoConfigReader.KEY_VISION_APPAUTO_LOCATE), BizErrorCode.FORBIDDEN);
        }
        ExecTaskInfo task = execTaskService.get(request.taskId());
        return aiServiceVisionClient.locate(request.withProjectId(task.projectId()));
    }
}
