package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ScriptSourceContract;
import com.sixbean.testpike.common.util.ScriptStyleContract;
import com.sixbean.testpike.common.util.ScriptStyleViolation;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class ScriptStyleGate {

    private final SystemConfigReader systemConfigReader;

    public ScriptStyleGate(SystemConfigReader systemConfigReader) {
        this.systemConfigReader = systemConfigReader;
    }

    
    public void check(int generatedBy, String actionJson) {
        if (generatedBy != ScriptSourceContract.GENERATED_BY_CODEGEN
                || !systemConfigReader.getBoolean(ScriptStyleContract.CONFIG_KEY_ENABLED, false)) {
            return;
        }
        List<ScriptStyleViolation> violations = ScriptStyleContract.check(actionJson);
        if (!violations.isEmpty()) {
            throw new ServiceException("脚本未通过团队规范闸："
                    + ScriptStyleContract.describe(violations)
                    + "——请在脚本编辑页按上述条目逐步修正后重新保存；"
                    + "如需停用该规范闸，请在「系统设置」把配置键 script.style-gate.enabled 调为关闭",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
