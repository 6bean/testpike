package com.sixbean.testpike.core.plans.api.dto;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;


public record ExitCriteria(Double minPassRate, boolean noOpenDefects,
                           String evalRubricName, String evalRubricDomain,
                           Double evalMinPassRate) {

    public static ExitCriteria of(Double minPassRate, Boolean noOpenDefects) {
        double rate = minPassRate == null ? 0.95 : minPassRate;
        if (rate <= 0 || rate > 1) {
            throw new ServiceException("准出通过率阈值需在 0~1 之间（如 0.95 表示 95%）——请在「测试计划」编辑区「准入准出」步骤改填，正确写法：0.95",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new ExitCriteria(rate, noOpenDefects == null || noOpenDefects, null, null, null);
    }

    
    public static ExitCriteria withEvalGate(Double minPassRate, Boolean noOpenDefects,
                                            String evalRubricName, String evalRubricDomain,
                                            Double evalMinPassRate) {
        ExitCriteria base = of(minPassRate, noOpenDefects);
        if (evalRubricName == null || evalRubricName.isBlank()) {
            throw new ServiceException("AI 评测门禁需选择评分标准族名（挂某 Rubric 的达标线才计入准出）——请在「准入准出」区选择「裁判标准」后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (evalRubricDomain == null || evalRubricDomain.isBlank()) {
            throw new ServiceException("AI 评测门禁需同时给出评分标准业务域（族名×业务域定位标准族）——请在「准入准出」区选定「裁判标准」后同时选择其业务域",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (evalMinPassRate == null) {
            throw new ServiceException("AI 评测门禁的达线率阈值必填（0~1，如 0.8 表示评测达线率需 80%）——请在「准入准出」区填写达线率，正确写法：0.8",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (evalMinPassRate <= 0 || evalMinPassRate > 1) {
            throw new ServiceException("AI 评测门禁达线率阈值需在 0~1 之间（如 0.8 表示 80%）——请在「准入准出」区改为 0~1 之间的小数，正确写法：0.8",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new ExitCriteria(base.minPassRate(), base.noOpenDefects(),
                evalRubricName.trim(), evalRubricDomain.trim(), evalMinPassRate);
    }

    
    public boolean evalGateEnabled() {
        return evalRubricName != null && !evalRubricName.isBlank()
                && evalRubricDomain != null && !evalRubricDomain.isBlank()
                && evalMinPassRate != null;
    }
}
