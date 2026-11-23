package com.sixbean.testpike.core.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.domain.component.AppautoMatrixDeviceResolver;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class AppautoExploreEnvelopeEnricher {

    private final AppAutoDispatchSupport dispatchSupport;
    private final AppautoMatrixDeviceResolver deviceResolver;
    private final CredPlaceholderInjector credPlaceholderInjector;
    private final ObjectMapper objectMapper;

    public AppautoExploreEnvelopeEnricher(AppAutoDispatchSupport dispatchSupport,
                                          AppautoMatrixDeviceResolver deviceResolver,
                                          CredPlaceholderInjector credPlaceholderInjector,
                                          ObjectMapper objectMapper) {
        this.dispatchSupport = dispatchSupport;
        this.deviceResolver = deviceResolver;
        this.credPlaceholderInjector = credPlaceholderInjector;
        this.objectMapper = objectMapper;
    }

    
    @SuppressWarnings("unchecked")
    public String enrich(long projectId, String actionJson, String sessionCredRef, long sessionId) {
        Map<String, Object> envelope = dispatchSupport.parseEnvelopeTolerant(actionJson);
        if (envelope == null || !ExploreEnvelopeContract.isMobileEnvelope(
                str(envelope.get("kind")), str(envelope.get("target")))) {
            return actionJson;
        }
        AppautoEngineInfo engine = dispatchSupport.resolveDefaultEngine();
        envelope.put("engine", dispatchSupport.engineProjection(engine));
        Map<String, Object> leaseRef = resolveLeaseRef(projectId, engine, sessionId);
        if (leaseRef == null) {
            throw new ServiceException("App 探索没有可用设备——项目内无引擎「" + engine.name()
                    + "」可寻址的设备档案（隔离中/过热冷却窗内/未登记都不算可用）。请先在"
                    + "「APP 自动化 → 设备墙」登记并启用设备，或等过热设备冷却后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        envelope.put("leaseRef", leaseRef);
        envelope.put("runId", "explore-" + sessionId);
        if (sessionCredRef != null && !sessionCredRef.isBlank()) {
            envelope.put("arguments", withCredPlaceholder(envelope.get("arguments"), sessionCredRef));
        }
        List<Map<String, Object>> injected =
                credPlaceholderInjector.inject(projectId, List.of(envelope));
        credPlaceholderInjector.assertNoLeftover(injected);
        try {
            return objectMapper.writeValueAsString(injected.get(0));
        } catch (Exception e) {
            throw new ServiceException("探索信封富化序列化失败", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private Map<String, Object> resolveLeaseRef(long projectId, AppautoEngineInfo engine,
                                                long sessionId) {
        for (String deviceKind : engine.deviceKindCodes()) {
            Map<String, Object> ref = deviceResolver.resolve(projectId, deviceKind,
                    (int) Math.floorMod(sessionId, 7));
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    
    @SuppressWarnings("unchecked")
    private Map<String, Object> withCredPlaceholder(Object arguments, String credRef) {
        Map<String, Object> args = arguments instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        args.putIfAbsent("cred", "{{CRED:" + credRef.strip() + "}}");
        return args;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
