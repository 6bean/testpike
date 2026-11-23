package com.sixbean.testpike.core.script.domain.component;

import org.springframework.stereotype.Component;


@Component
public class ScriptStaticValidator {

    private final ScriptLimitGuard scriptLimitGuard;
    private final ExpectMatchValidator expectMatchValidator;
    private final NonUiStepValidator nonUiStepValidator;
    private final CodeStepValidator codeStepValidator;
    private final VisualAssertValidator visualAssertValidator;
    private final ManualEditValidator manualEditValidator;
    private final ScriptStyleGate scriptStyleGate;

    public ScriptStaticValidator(ScriptLimitGuard scriptLimitGuard,
                                 ExpectMatchValidator expectMatchValidator,
                                 NonUiStepValidator nonUiStepValidator,
                                 CodeStepValidator codeStepValidator,
                                 VisualAssertValidator visualAssertValidator,
                                 ManualEditValidator manualEditValidator,
                                 ScriptStyleGate scriptStyleGate) {
        this.scriptLimitGuard = scriptLimitGuard;
        this.expectMatchValidator = expectMatchValidator;
        this.nonUiStepValidator = nonUiStepValidator;
        this.codeStepValidator = codeStepValidator;
        this.visualAssertValidator = visualAssertValidator;
        this.manualEditValidator = manualEditValidator;
        this.scriptStyleGate = scriptStyleGate;
    }

    
    public void check(long projectId, int generatedBy, String actionJson, Long operatorId) {
        scriptLimitGuard.check(actionJson);
        expectMatchValidator.check(actionJson, projectId, operatorId);
        nonUiStepValidator.check(actionJson, projectId, operatorId);
        manualEditValidator.check(projectId, generatedBy, actionJson);
        scriptStyleGate.check(generatedBy, actionJson);
        codeStepValidator.check(actionJson, projectId, operatorId);
        visualAssertValidator.check(actionJson);
    }
}
