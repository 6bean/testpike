package com.sixbean.testpike.aiservice.judge.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Component
public class EvalTargetAdapterRegistry {

    
    static final String DEFAULT_ADAPTER = "http";

    private final Map<String, EvalTargetAdapter> byType;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvalTargetAdapterRegistry(List<EvalTargetAdapter> adapters) {
        this.byType = adapters.stream().collect(Collectors.toUnmodifiableMap(
                a -> a.type().toLowerCase(), a -> a));
    }

    
    public EvalTargetAdapter resolve(JudgeRunBrief run) {
        String ref = run.targetRef();
        if (ref == null || ref.isBlank()) {
            throw new ServiceException("外部被测系统的运行缺少被评目标配置（target_ref 为空）："
                    + "runId=" + run.id() + "——请在评测运行配置端点与映射表单");
        }
        String adapter;
        try {
            Object parsed = objectMapper.readValue(ref, Map.class).get("adapter");
            adapter = parsed == null ? DEFAULT_ADAPTER : String.valueOf(parsed).toLowerCase();
        } catch (Exception e) {
            throw new ServiceException("被评目标配置 target_ref 不是合法 JSON 对象（runId="
                    + run.id() + "）——表单式映射要求 {\"url\":...,\"outputJsonPath\":...} 形态");
        }
        EvalTargetAdapter target = byType.get(adapter);
        if (target == null) {
            throw new ServiceException("被评目标适配器「" + adapter + "」暂不支持（当前支持："
                    + String.join("、", byType.keySet()) + "）——runId=" + run.id()
                    + "；多轮会话/WS 类深度适配=详设 §6.5 二期位");
        }
        return target;
    }
}
