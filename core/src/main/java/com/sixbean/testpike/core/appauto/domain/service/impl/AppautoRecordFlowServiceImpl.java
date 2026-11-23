package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftStepInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoIrStepGate;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoRecordDraftService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoRecordFlowService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceRecordFlowClient;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Service
public class AppautoRecordFlowServiceImpl implements AppautoRecordFlowService {

    private final AppautoConfigReader configReader;
    private final AppautoEngineService engineService;
    private final ExecTaskService execTaskService;
    private final AiServiceRecordFlowClient recordFlowClient;
    private final AppautoRecordDraftService draftService;
    private final AppautoIrStepGate stepGate;
    private final ObjectMapper objectMapper;

    public AppautoRecordFlowServiceImpl(AppautoConfigReader configReader,
                                        AppautoEngineService engineService,
                                        ExecTaskService execTaskService,
                                        AiServiceRecordFlowClient recordFlowClient,
                                        AppautoRecordDraftService draftService,
                                        AppautoIrStepGate stepGate,
                                        ObjectMapper objectMapper) {
        this.configReader = configReader;
        this.engineService = engineService;
        this.execTaskService = execTaskService;
        this.recordFlowClient = recordFlowClient;
        this.draftService = draftService;
        this.stepGate = stepGate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AppautoRecordFlowView generate(AppautoRecordFlowRequest request) {
        if (!configReader.recordToFlowEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("录制转流程",
                    AppautoConfigReader.KEY_RECORD_TO_FLOW), BizErrorCode.FORBIDDEN);
        }
        if (request == null || request.taskId() == null) {
            throw new ServiceException("录制反推缺少来源任务号（项目归属由任务行权威反查）"
                    + "——请在「录制转流程」面板从录制会话发起反推", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.engineCode() == null || request.engineCode().isBlank()) {
            throw new ServiceException("录制反推缺少目标引擎——不同引擎能用的动作不一样，"
                    + "裁剪需要引擎依据——请在「录制转流程」面板选择目标引擎后重试", BizErrorCode.VALIDATION_ERROR);
        }
        String eventSource = request.eventSource() == null ? ""
                : request.eventSource().trim().toLowerCase(Locale.ROOT);
        if (!AppautoRecordDraftService.EVENT_SOURCES.contains(eventSource)) {
            throw new ServiceException("录制反推缺少采集档位（native 引擎原生事件流 / "
                    + "scrcpy 安卓回落输入注入流）——档位由执行器录制装配层判定，"
                    + "请到「引擎中心」核对该引擎支持的档位",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.events() == null || request.events().isEmpty()) {
            throw new ServiceException("录制反推缺少事件序列（无录制素材不烧模型）"
                    + "——请先完成一次录制会话，再在「录制转流程」发起反推", BizErrorCode.VALIDATION_ERROR);
        }
        String engineCode = request.engineCode().trim().toUpperCase(Locale.ROOT);
        AppautoEngineInfo engine = engineService.getByCode(engineCode);
        if (engine.status() != 1) {
            throw new ServiceException("引擎「" + engine.name() + "（" + engine.code()
                    + "）」已下架，不能作为反推目标——请换用引擎目录里在架的引擎",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> allowedVerbs = stepGate.allowedVerbs(engine);
        if (allowedVerbs.isEmpty()) {
            throw new ServiceException("引擎「" + engine.name() + "」能力位图未登记任何 IR 基线动词，"
                    + "无法裁剪反推范围——请到「引擎中心」核对该引擎能力位图后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ExecTaskInfo task = execTaskService.get(request.taskId());

        List<Map<String, Object>> steps = recordFlowClient.generate(
                new AppautoRecordFlowLlmRequest(task.projectId(), engineCode, eventSource,
                        request.events(), allowedVerbs));
        if (steps == null || steps.isEmpty()) {
            throw new ServiceException("模型没有反推出任何步骤（应答为空）——请到「录制转流程」确认录制事件序列"
                    + "覆盖有效操作，或稍后重试", BizErrorCode.VALIDATION_ERROR);
        }
        List<AppautoRecordDraftStepInput> draftSteps = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            draftSteps.add(toDraftStep(steps.get(i), allowedVerbs, i + 1));
        }
        long draftId = draftService.record(new AppautoRecordDraftInput(
                task.id(), eventSource, request.title(), draftSteps));
        return new AppautoRecordFlowView(draftId, draftSteps.size());
    }

    
    private AppautoRecordDraftStepInput toDraftStep(Map<String, Object> step,
                                                    List<String> allowedVerbs, int stepNo) {
        stepGate.checkStep(step, allowedVerbs, stepNo);
        Object rawTarget = step.get("target");
        String target;
        if (rawTarget == null) {
            target = "{}";
        } else if (rawTarget instanceof Map<?, ?> locator) {
            try {
                target = objectMapper.writeValueAsString(locator);
            } catch (Exception e) {
                throw new ServiceException("模型应答第 " + stepNo
                        + " 步的定位器对象无法序列化（" + e.getMessage() + "），本次反推不采用"
                        + "——请在「录制转流程」重试；持续失败请简化该步定位",
                        BizErrorCode.VALIDATION_ERROR);
            }
        } else {
            throw new ServiceException("模型应答第 " + stepNo + " 步的定位策略不是定位器对象"
                    + "（当前=" + rawTarget + "）——草稿入库要求对象形（正确写法示例 {\"text\":\"登录\"}），"
                    + "本次反推已整体拒绝，请重试", BizErrorCode.VALIDATION_ERROR);
        }
        Object value = step.get("value");
        Object reason = step.get("reason");
        return new AppautoRecordDraftStepInput(stepNo, String.valueOf(step.get("action")),
                target, value == null ? null : String.valueOf(value),
                reason == null ? null : String.valueOf(reason));
    }
}
