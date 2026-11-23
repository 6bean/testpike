package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ActionCheckResult;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.springframework.stereotype.Component;


@Component
public class ManualEditValidator {

    private final ActionJsonValidator actionJsonValidator;
    private final com.sixbean.testpike.core.apitest.domain.service.StepAliasService stepAliasService;

    public ManualEditValidator(ActionJsonValidator actionJsonValidator,
                               com.sixbean.testpike.core.apitest.domain.service.StepAliasService stepAliasService) {
        this.actionJsonValidator = actionJsonValidator;
        this.stepAliasService = stepAliasService;
    }

    
    public void check(long projectId, int generatedBy, String actionJson) {
        if (generatedBy != GENERATED_BY_MANUAL) {
            return;
        }
        ActionCheckResult result = actionJsonValidator.validate(
                actionJson, stepAliasService.aliasNames(projectId));
        if (!result.passed()) {
            throw new ServiceException("脚本内容校验未通过：" + String.join("；", result.errors()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public static final int GENERATED_BY_MANUAL =
            com.sixbean.testpike.common.util.ScriptSourceContract.GENERATED_BY_MANUAL;
}
