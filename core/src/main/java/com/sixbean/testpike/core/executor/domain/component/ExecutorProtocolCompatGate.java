package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseRangeRules;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import org.springframework.stereotype.Component;


@Component
public class ExecutorProtocolCompatGate {

    private final ReleaseManifestService manifestService;
    private final VersionUnsupportedAlerter alerter;

    public ExecutorProtocolCompatGate(ReleaseManifestService manifestService,
                                      VersionUnsupportedAlerter alerter) {
        this.manifestService = manifestService;
        this.alerter = alerter;
    }

    
    public void assertRegisterAllowed(String name, String version) {
        ReleaseManifestInfo latest = manifestService.latest();
        if (ReleaseRangeRules.allowed(latest, version)) {
            return;
        }
        String range = ReleaseRangeRules.rangeText(latest);
        alerter.alert(name, "执行器 " + name + "（版本 " + version + "）与本平台协议不兼容（需 "
                + range + "），已拒绝入池；请先升级节点 agent 后重新注册。");
        throw new ServiceException("执行器 " + name + " 与本平台协议不兼容（需 "
                + range + "）——请前往「升级中心」查看平台版本要求，升级节点 agent 后重新注册",
                BizErrorCode.VALIDATION_ERROR);
    }

    
    public boolean dispatchable(String version) {
        return ReleaseRangeRules.allowed(manifestService.latest(), version);
    }

    
    public void alertIfIncompatible(String name, String version) {
        ReleaseManifestInfo latest = manifestService.latest();
        if (ReleaseRangeRules.allowed(latest, version)) {
            return;
        }
        alerter.alert(name, "执行器 " + name + "（版本 " + version + "）与本平台协议不兼容（需 "
                + ReleaseRangeRules.rangeText(latest) + "），任务已停止派发；请升级节点 agent，升级后自动恢复。");
    }
}
