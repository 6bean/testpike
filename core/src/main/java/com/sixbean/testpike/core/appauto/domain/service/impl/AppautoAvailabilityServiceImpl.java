package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineAvailabilityInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAvailabilityService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class AppautoAvailabilityServiceImpl implements AppautoAvailabilityService {

    
    private static final String CAPABILITY_ENGINES_KEY = "engines";

    private final AppautoEngineService engineService;
    private final ExecutorService executorService;
    private final AppAutoDispatchSupport dispatchSupport;

    public AppautoAvailabilityServiceImpl(AppautoEngineService engineService,
                                          ExecutorService executorService,
                                          AppAutoDispatchSupport dispatchSupport) {
        this.engineService = engineService;
        this.executorService = executorService;
        this.dispatchSupport = dispatchSupport;
    }

    @Override
    public List<AppautoEngineAvailabilityInfo> availability(boolean includeDisabled) {
        List<AppautoEngineInfo> engines = engineService.listEngines(includeDisabled);
        List<Map<String, Object>> onlineCaps = executorService.onlineCapabilities();
        int onlineCount = onlineCaps.size();
        List<AppautoEngineAvailabilityInfo> rows = new ArrayList<>(engines.size());
        for (AppautoEngineInfo engine : engines) {
            rows.add(reconcile(engine, onlineCaps, onlineCount));
        }
        return rows;
    }

    private AppautoEngineAvailabilityInfo reconcile(AppautoEngineInfo engine,
                                                    List<Map<String, Object>> onlineCaps,
                                                    int onlineCount) {
        if (engine.status() != 1) {
            return new AppautoEngineAvailabilityInfo(engine.code(), false, 0, List.of(
                    "引擎「" + engine.name() + "」已停用/下架，不在用户可选面——"
                            + "如需启用请到引擎中心（管理）恢复上架",
                    healthFaceNote()));
        }
        long reported = onlineCaps.stream()
                .filter(caps -> dispatchSupport.covers(caps, CAPABILITY_ENGINES_KEY, engine.code()))
                .count();
        if (reported > 0) {
            return new AppautoEngineAvailabilityInfo(engine.code(), true, (int) reported, List.of());
        }
        List<String> reasons = new ArrayList<>(2);
        if (onlineCount == 0) {
            reasons.add("当前无在线执行器——引擎可用性待执行器注册心跳后自动刷新");
        } else {
            reasons.add("在线执行器 " + onlineCount + " 台均未上报「" + engine.name()
                    + "」能力——请在执行器机安装对应引擎运行时并重新注册"
                    + "（能力=执行器自探测如实上报，缺件不申报）；安装指引见引擎中心");
        }
        reasons.add(healthFaceNote());
        return new AppautoEngineAvailabilityInfo(engine.code(), false, 0, List.copyOf(reasons));
    }

    private String healthFaceNote() {
        return "对账口径=引擎目录 ∩ 执行器能力上报两面；机器面缺件明细（版本/工具/探针）请到引擎中心「体检」页查看（三面）";
    }
}
