package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;

import java.util.ArrayList;
import java.util.List;


class GatewayRecordingStub extends CoreGateway {

    final List<LlmAuditRequest> audits = new ArrayList<>();
    final List<String> notifications = new ArrayList<>();
    List<LlmConfigBrief> briefs = List.of();
    LlmConfigBrief projectBrief;
    String timeoutConfigValue;

    GatewayRecordingStub() {
        super("http://localhost:1");
    }

    @Override
    public LlmConfigBrief getProjectLlmConfig(long projectId) {
        return projectBrief;
    }

    @Override
    public String getSysConfig(String key) {
        return "llm.timeout-seconds".equals(key) ? timeoutConfigValue : null;
    }

    @Override
    public List<LlmConfigBrief> listEnabledLlmConfigs() {
        return briefs;
    }

    @Override
    public void writeAudit(LlmAuditRequest audit) {
        audits.add(audit);
    }

    @Override
    public void createNotification(long userId, Long projectId, String eventType,
                                   String title, String content, String dedupKey) {
        notifications.add(eventType + "|" + content);
    }
}
