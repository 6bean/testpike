package com.sixbean.testpike.aiservice.llm.domain.component;


public enum SkillStage {

    
    F2_EXTRACT(1),
    
    CASE_GEN(2),
    
    SCRIPT_GEN(3),
    
    REPAIR_LOOP(4);

    private final int code;

    SkillStage(int code) {
        this.code = code;
    }

    
    public int code() {
        return code;
    }

    
    public boolean accepts(int role) {
        return switch (role) {
            case 1 -> this == SCRIPT_GEN;
            case 2 -> this == REPAIR_LOOP;
            case 3 -> true;
            default -> false;
        };
    }
}
