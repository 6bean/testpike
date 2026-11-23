package com.sixbean.testpike.core.document.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class DocDimensionSuggester {

    
    public static final String EXCEPTION_FLOW = "异常流";
    public static final String BOUNDARY = "边界";
    public static final String NON_FUNCTIONAL = "非功能";

    
    private static final List<String> ALL_DIMENSIONS =
            List.of(EXCEPTION_FLOW, BOUNDARY, NON_FUNCTIONAL);

    private static final Map<String, List<String>> KEYWORDS = buildKeywords();

    private static Map<String, List<String>> buildKeywords() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(EXCEPTION_FLOW, List.of("异常", "错误", "失败", "故障", "重试", "回滚",
                "超时", "并发冲突", "冲突"));
        m.put(BOUNDARY, List.of("上限", "下限", "最大", "最小", "最多", "最少",
                "至少", "至多", "边界", "范围", "精度", "为空", "超长", "越界"));
        m.put(NON_FUNCTIONAL, List.of("性能", "响应时间", "吞吐", "并发量", "资源占用",
                "耗时", "容量", "可用性", "安全", "加密", "脱敏", "权限", "兼容"));
        return m;
    }

    
    public List<String> suggest(List<String> itemTexts) {
        List<String> missing = new ArrayList<>();
        for (String dimension : ALL_DIMENSIONS) {
            if (!presented(dimension, itemTexts)) {
                missing.add(dimension);
            }
        }
        return missing;
    }

    /** -③b request-side whitelist: is the name one of the known dimensions? */
    public boolean isKnown(String name) {
        return name != null && ALL_DIMENSIONS.contains(name);
    }

    /** -③b: the closed vocabulary as on-screen names (error copy single source). */
    public List<String> knownDimensions() {
        return ALL_DIMENSIONS;
    }

    private boolean presented(String dimension, List<String> itemTexts) {
        List<String> keywords = KEYWORDS.get(dimension);
        for (String text : itemTexts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = text.replaceAll("\\s+", "");
            for (String keyword : keywords) {
                if (normalized.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }
}
