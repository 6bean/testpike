package com.sixbean.testpike.core.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;

import java.util.List;

/**
 * Shared drafts for script write-back suites (single source for the v2 action JSON,
 * the evidence package and the pending-review case builder; both the direct write-back
 * suite and the in-session confirmation suite consume this instead of copying the same
 * fixtures — R40).
 */
public final class ScriptWritebackDrafts {

    /** Canonical agent-authored v2 action JSON used across write-back suites. */
    public static final String ACTION_JSON = """
            {"version":"v2","caseId":"TC-WBT-001","title":"登录功能测试","endType":"web",
             "steps":[{"action":"input","target":{"css":"#username","chain":["css"]},"value":"a"},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"dev"}}
            """;

    private ScriptWritebackDrafts() {
    }

    /** A complete evidence package (ran-at time, fingerprint, counts, log digest). */
    public static ScriptWritebackEvidence evidence() {
        return new ScriptWritebackEvidence("2026-11-17T20:00:00+08:00", "linux-x86_64/chrome-131",
                12, 0, "sha256:6f2c1a9e0b4d7c31a5e8f02b94d7c1a30e5b8d24f60a1c93e7b5d2048af61c30");
    }

    /** Create a web case in pending state and return its id. */
    public static long createCase(CaseService caseService, long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "登录功能测试", "打开系统登录页",
                "登录成功进入首页", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "页面出现登录成功提示", 1)));
        return caseService.create(1L, input).id();
    }

    /** Serialize a canonical write-back draft payload (what the confirmation loop replays). */
    public static String payloadJson(ObjectMapper objectMapper, long caseId) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "caseId", caseId,
                    "schemaVersion", "v2",
                    "actionJson", ACTION_JSON,
                    "evidence", objectMapper.convertValue(evidence(), java.util.Map.class),
                    "expectedVersion", NullNode.getInstance(),
                    "operatorId", 1L));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("write-back draft payload could not be serialized", e);
        }
    }
}
