package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers the built-in login-state registration tool
 * ({@code session.login-state.register}) of the dialog screenshot capture
 * batch .
 * <p>Like the popup-rule learn tool, the dialog chain NEVER executes this
 * tool: the schema carries {@code x-write=true} so the dispatcher refuses a
 * token-less call, and the emitter journals a confirmable draft card instead
 * — routing comes from the {@code draftObjectType=LOGIN_STATE_WRITE} token
 * in the exec spec (registry-driven, no orchestrator name literals). The
 * HTTP url is a form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: the credential VALUES never enter the conversation. The
 * tool only names the transient parse slot ({@code stagingToken}), the new
 * state row ({@code stateName}) and the ticked candidate names; confirming
 * the card re-reads the values from the TTL-bound slot and files them
 * through the SAME {@code LoginStateService.register} ledger chain the CDP
 * capture wizard uses — vault encryption, same-name renewal, replay
 * verification and the four-state light stay single-sourced, and the dialog
 * is structurally unable to bypass one gate. The owning project comes from
 * the chat session, and the slot records the session that staged it, so a
 * forged call can neither write into another project's ledger nor drain
 * another conversation's parsed material.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class LoginStateRegisterToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoginStateRegisterToolSeedInitializer.class);

    static final String TOOL_LOGIN_STATE_REGISTER = "session.login-state.register";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_LOGIN_STATE_REGISTER);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/session/login-state/register";

    /** Package-private for the contract test that parses this schema. */
    static final String SCHEMA_LOGIN_STATE_REGISTER =
            "{\"type\":\"object\",\"x-write\":true,"
            + "\"properties\":{"
            + "\"stagingToken\":{\"type\":\"string\",\"description\":\"服务端截图解析结果的暂存凭据（来自本次对话中系统给出的解析摘要，不要自行编造；条目取值保存在暂存槽内，不出现在对话内容中）\"},"
            + "\"stateName\":{\"type\":\"string\",\"description\":\"新登录态名称（必填，同一项目内重名将按续期规则覆盖旧值；用户未给出名称时，直接按截图中的站点或用途自行拟定一个简洁名称并继续调用，不要向用户追问名称）\"},"
            + "\"entries\":{\"type\":\"array\",\"description\":\"要登记的候选条目清单（只写名称与是否勾选；凭证值一律不要填写，填写也会被系统丢弃；用户未指明勾选哪些时，默认将解析出的候选行全选）\","
            + "\"items\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"selected\":{\"type\":\"boolean\"}},\"required\":[\"name\"]}}"
            + "},"
            + "\"required\":[\"stagingToken\",\"stateName\",\"entries\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public LoginStateRegisterToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            log.info("内置登录态登记写工具种子核对完成：新注册 {} 件（session.login-state.register，走审批链发布态；0=已在位）", created);
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason is surfaced
            // as ERROR so the log-noise gate always sees a broken schema.
            log.error("内置登录态登记写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage(), e);
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('session.login-state.register')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置登录态登记写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
                continue;
            }
            registerPublished(intent);
            created++;
        }
        return created;
    }

    /** True when a row already carries the contract name in the system-level bucket. */
    private boolean nameSquatted(String toolName) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ? AND project_id IS NULL"
                        + " AND (exec_spec ->> 'intent' IS NULL"
                        + " OR exec_spec ->> 'intent' <> ?)",
                Integer.class, toolName, toolName);
        return n != null && n > 0;
    }

    private void registerPublished(String intent) {
        String spec = "{\"url\":\"" + PLACEHOLDER_URL + "\",\"intent\":\"" + intent
                + "\",\"draftObjectType\":\"LOGIN_STATE_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "登录态采集",
                "把本次对话中服务端解析出的浏览器登录态截图（Cookie / Local Storage 候选表）按勾选登记为登录态；"
                        + "确认后走登录态管理页同一入册链，加密入保险库、同名续期与执行机重放核验照常复核，不通过会被拒绝并提示原因；"
                        + "凭证取值只保存在服务端暂存槽并在确认时读取，不会出现在对话内容与草案记录里；暂存槽过期后需重新粘贴截图生成提案",
                null,
                "[\"登录态\", \"内置\"]",
                SCHEMA_LOGIN_STATE_REGISTER,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
