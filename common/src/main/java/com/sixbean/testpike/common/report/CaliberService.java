package com.sixbean.testpike.common.report;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class CaliberService {

    private static final List<CaliberDefinition> ALL = List.of(CaliberDefinition.values());

    private CaliberService() {
    }

 
    public static List<CaliberDefinition> listAll() {
        return ALL;
    }

    
    public static CaliberDefinition require(String code) {
        return ALL.stream()
                .filter(d -> d.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "统计口径不存在: " + code + "（可用口径请查 /reports/calibers）",
                        BizErrorCode.NOT_FOUND));
    }

    
    public static CaliberQuery resolve(String code, Map<String, Object> params) {
        CaliberDefinition def = require(code);
        List<String> declared = def.namedParams();
        Map<String, Object> supplied = params == null ? Map.of() : params;
        for (String p : supplied.keySet()) {
            if (!declared.contains(p)) {
                throw new ServiceException("口径 " + code + " 收到未声明参数: " + p
                        + "（声明参数=" + declared + "）", BizErrorCode.VALIDATION_ERROR);
            }
        }
        for (String p : declared) {
            if (!supplied.containsKey(p)) {
                throw new ServiceException("口径 " + code + " 缺少参数: " + p
                        + "（声明参数=" + declared + "）", BizErrorCode.VALIDATION_ERROR);
            }
        }
        Map<String, Object> copy = new HashMap<>(supplied);
        return new CaliberQuery(code, def.sqlTemplate(), copy);
    }

    
    public static Map<String, Object> scopeParams(Long projectId, Object fromTime, Object toTime) {
        Map<String, Object> m = new HashMap<>();
        m.put("projectId", projectId);
        m.put("fromTime", fromTime);
        m.put("toTime", toTime);
        m.put("projectIds", null);
        return m;
    }

    
    public static String projectSetParam(List<Long> projectIds) {
        if (projectIds == null) {
            return null;
        }
        StringBuilder literal = new StringBuilder("{");
        List<Long> sorted = new ArrayList<>(projectIds);
        for (int i = 0; i < sorted.size(); i++) {
            literal.append(i > 0 ? "," : "").append(sorted.get(i));
        }
        return literal.append("}").toString();
    }

    
    public static List<String> codes() {
        return ALL.stream().map(CaliberDefinition::code).toList();
    }
}
