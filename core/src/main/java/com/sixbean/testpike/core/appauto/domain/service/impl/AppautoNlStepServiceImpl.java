package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoIrStepGate;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoNlStepService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceNlStepClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;


@Service
public class AppautoNlStepServiceImpl implements AppautoNlStepService {

    private final AppautoConfigReader configReader;
    private final AppautoEngineService engineService;
    private final AiServiceNlStepClient nlStepClient;
    private final AppautoIrStepGate stepGate;

    public AppautoNlStepServiceImpl(AppautoConfigReader configReader,
                                    AppautoEngineService engineService,
                                    AiServiceNlStepClient nlStepClient,
                                    AppautoIrStepGate stepGate) {
        this.configReader = configReader;
        this.engineService = engineService;
        this.nlStepClient = nlStepClient;
        this.stepGate = stepGate;
    }

    @Override
    public AppautoNlStepView generate(AppautoNlStepRequest request) {
        if (!configReader.nlStepEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("自然语言步骤生成",
                    AppautoConfigReader.KEY_NL_STEP), BizErrorCode.FORBIDDEN);
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ServiceException("请先写一句中文操作描述（例如「点击登录按钮，输入用户名 admin」）"
                    + "——没有描述就生成步骤只能靠猜", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.engineCode() == null || request.engineCode().isBlank()) {
            throw new ServiceException("请先在 App 步骤区选择目标引擎再生成——不同引擎能用的动作不一样，"
                    + "裁剪需要引擎依据；引擎下拉位在「APP 自动化」步骤编辑器的「执行引擎」选择框，"
                    + "各引擎支持面可在「APP 自动化」→「引擎中心」查看", BizErrorCode.VALIDATION_ERROR);
        }
        String engineCode = request.engineCode().trim().toUpperCase(Locale.ROOT);
        AppautoEngineInfo engine = engineService.getByCode(engineCode);
        if (engine.status() != 1) {
            throw new ServiceException("引擎「" + engine.name() + "（" + engine.code()
                    + "）」已下架，不能作为生成目标——请换用引擎目录里在架的引擎",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> allowedVerbs = stepGate.allowedVerbs(engine);
        if (allowedVerbs.isEmpty()) {
            throw new ServiceException("引擎「" + engine.name() + "」能力位图未登记任何 IR 基线动词，"
                    + "无法裁剪生成范围——请在「APP 自动化」→「引擎中心」核对该引擎描述符 capabilities "
                    + "是否包含 tap/swipe/input 等基线动作后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        AppautoNlStepView answer = nlStepClient.generate(new AppautoNlStepLlmRequest(
                request.projectId(), engineCode, request.description().trim(), allowedVerbs));
        List<Map<String, Object>> steps = answer == null ? List.of() : answer.steps();
        if (steps == null || steps.isEmpty()) {
            throw new ServiceException("模型没有产出任何步骤（应答为空）——请把描述写得更具体一些"
                    + "（动作+对象，例如「打开首页，点击搜索框，输入关键字」），或稍后在步骤区重新点「生成」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (int i = 0; i < steps.size(); i++) {
            stepGate.checkStep(steps.get(i), allowedVerbs, i + 1);
        }
        return new AppautoNlStepView(steps);
    }
}
