package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.defect.DefectLabels;

import java.util.LinkedHashMap;
import java.util.Map;


public class AppautoHealthSamples {

    
    private static final int STATUS_SUCCESS = 3;

 
    private static final int AI_UNCLASSIFIED = 0;

    private int total;
    private int pass;
    private final Map<String, Integer> errorDist = new LinkedHashMap<>();
    private final Map<String, Integer> aiDist = new LinkedHashMap<>();

    
    public void accept(int status, Integer errorType, Integer aiClass) {
        total++;
        if (status == STATUS_SUCCESS) {
            pass++;
            return;
        }
        errorDist.merge(DefectLabels.errorTypeLabel(
                errorType == null ? AI_UNCLASSIFIED : errorType), 1, Integer::sum);
        aiDist.merge(aiFailureLabel(aiClass), 1, Integer::sum);
    }

    
    public int total() {
        return total;
    }

    
    public int passCount() {
        return pass;
    }

    
    public Map<String, Integer> errorDist() {
        return errorDist;
    }

 
    public Map<String, Integer> aiDist() {
        return aiDist;
    }

    private static String aiFailureLabel(Integer aiClass) {
        if (aiClass == null) {
            return "未归类";
        }
        return switch (aiClass) {
            case 1 -> "产品缺陷";
            case 2 -> "AI定位错误";
            case 3 -> "环境问题";
            default -> "未归类";
        };
    }
}
