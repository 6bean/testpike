package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Component
public class AppautoIrStepGate {

    
    private static final int APP_PREFIX_LEN = UiActionContract.APP_NAMESPACE.length();

    
    public List<String> allowedVerbs(AppautoEngineInfo engine) {
        return UiActionContract.prunedVerbs(engine.capabilities());
    }

    
    public void checkStep(Map<String, Object> step, List<String> allowedVerbs, int stepNo) {
        Object action = step == null ? null : step.get("action");
        if (action == null || String.valueOf(action).isBlank()) {
            throw new ServiceException("模型应答第 " + stepNo + " 步缺少动作名（action 字段）"
                    + "——步形不完整，请重试或把描述写得更具体（正确写法示例：打开登录页并点击登录按钮）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String actionName = String.valueOf(action);
        if (!UiActionContract.isAppAction(actionName)) {
            throw new ServiceException("模型应答第 " + stepNo + " 步「" + actionName
                    + "」不是 app. 前缀的抽象动作——自然语言步骤只产 app.* IR 动作"
                    + "（换引擎零改用例是 IR 的命门），请重试，或改成「app.launch」这类 app. 前缀动作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String violation = UiActionContract.appActionViolation(actionName);
        if (violation != null) {
            throw new ServiceException("模型应答第 " + stepNo + " 步被拒：" + violation
                    + "——请按被拒原因改写该步描述后重试；自然语言步骤可在「用例详情」重新生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> allowed = allowedVerbs.stream().collect(Collectors.toSet());
        String verb = actionName.substring(APP_PREFIX_LEN);
        if (!allowed.contains(verb)) {
            throw new ServiceException("模型应答第 " + stepNo + " 步「" + actionName
                    + "」超出目标引擎能力位图（该引擎不支持这个动作）——本次生成已整体拒绝，"
                    + "请前往「引擎中心」换选支持该动作的引擎，或改写描述", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
