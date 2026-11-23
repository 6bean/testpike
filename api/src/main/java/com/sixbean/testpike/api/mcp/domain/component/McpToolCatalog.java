package com.sixbean.testpike.api.mcp.domain.component;

import java.util.List;
import java.util.Optional;

/**
 * <p>Mock self-serve family: list/inspect reads ride mcp.read, create_stub/publish
 * writes ride a dedicated mcp.mock scope so an agent can verify its own mocks
 * without holding publish power.
 *
 */
public class McpToolCatalog {

 
    public static final String SCOPE_CASE = "mcp.case";
    public static final String SCOPE_READ = "mcp.read";
    /** Mock-family scope : grants the two mock write tools only —
 * read-side mock visibility stays under mcp.read so agents can self-serve
 * inspection without publish power. */
    public static final String SCOPE_MOCK = "mcp.mock";
    /** Document-channel scope (BH ruling batch): doc.upload/doc_task.poll/
     *  doc_task.cancel ride this scope; the token value domain already accepts
     *  any mcp.* literal, so validation needs no change. */
    public static final String SCOPE_DOC = "mcp.doc";
    /** Generation-trigger scope (BH ruling batch): gen.cases_from_requirement. */
    public static final String SCOPE_GEN = "mcp.gen";
    /** Script write-back scope (BH ruling batch): script.writeback drafts. */
    public static final String SCOPE_SCRIPT = "mcp.script";
    /** In-session confirmation scope (11-16 ruling): mcp.review.confirm settles
     *  pending drafts inside the agent conversation — no platform login needed. */
    public static final String SCOPE_REVIEW = "mcp.review";
    /** Execution-channel scope: exec.run / exec.status / exec.cancel /
     *  report.fetch. The scope value domain already accepts any mcp.* literal,
     *  so token validation needs no change. */
    public static final String SCOPE_EXEC = "mcp.exec";

    /** GA3 per-token usage read face . Dispatched by name in the
 * server handler — its only input is the token row the call itself presented. */
    public static final String TOOL_TOKENS_USAGE = "tokens.usage";

    private final List<McpToolDefinition> tools;

    public McpToolCatalog() {
        this.tools = java.util.stream.Stream.of(
                new McpToolDefinition("create_case",
                        "按需求文本创建测试用例：落库为「待评审」状态（不直入已评审——评审质量门不破），"
                                + "评审通过后由平台侧流转。须 mcp.case 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_CASE,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"requirement_id\":{\"type\":\"integer\",\"description\":\"关联需求点 ID（可选）\"},"
                                + "\"title\":{\"type\":\"string\",\"description\":\"用例标题\"},"
                                + "\"steps\":{\"type\":\"string\",\"description\":\"操作步骤\"},"
                                + "\"expected\":{\"type\":\"string\",\"description\":\"预期结果\"}"
                                + "},\"required\":[\"project_id\",\"title\",\"steps\",\"expected\"]}"),
                new McpToolDefinition("delete_case",
                        "删除测试用例：不立即删除——生成删除审批单（tp_delete_request），审批通过后才软删并"
                                + "进入回收站（30 天可恢复）。须 mcp.case 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_CASE,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"case_id\":{\"type\":\"integer\",\"description\":\"用例 ID\"},"
                                + "\"reason\":{\"type\":\"string\",\"description\":\"删除理由（进审批单，供审批人判断）\"}"
                                + "},\"required\":[\"project_id\",\"case_id\",\"reason\"]}"),
                new McpToolDefinition("get_cases",
                        "查询项目测试用例列表（只读，走项目 RBAC 隔离）：支持按用例集/关键词过滤与分页。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"suite_id\":{\"type\":\"integer\",\"description\":\"用例集 ID（可选）\"},"
                                + "\"keyword\":{\"type\":\"string\",\"description\":\"标题关键词（可选）\"},"
                                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，默认 1\"},"
                                + "\"size\":{\"type\":\"integer\",\"description\":\"每页条数，默认 20，上限 100\"}"
                                + "},\"required\":[\"project_id\"]}"),
                new McpToolDefinition("get_exec_result",
                        "查询用例最近一次执行结果（只读，走项目 RBAC 隔离）：含状态/耗时/失败原因摘要。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"case_id\":{\"type\":\"integer\",\"description\":\"用例 ID\"}"
                                + "},\"required\":[\"project_id\",\"case_id\"]}"),
                new McpToolDefinition("get_script",
                        "查询用例的自动化脚本（只读，走项目 RBAC 隔离）：返回动作 JSON Schema v2 脚本正文与版本。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"case_id\":{\"type\":\"integer\",\"description\":\"用例 ID\"}"
                                + "},\"required\":[\"project_id\",\"case_id\"]}"),
                new McpToolDefinition("list_requirements",
                        "查询项目需求点列表（只读，走项目 RBAC 隔离）：支持关键词过滤与分页。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"keyword\":{\"type\":\"string\",\"description\":\"关键词（可选）\"},"
                                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，默认 1\"},"
                                + "\"size\":{\"type\":\"integer\",\"description\":\"每页条数，默认 20，上限 100\"}"
                                + "},\"required\":[\"project_id\"]}"),
                new McpToolDefinition("tag_case",
                        "给测试用例打标签（复用平台标签字典，M3 标签体系）：须 mcp.case 写权限，"
                                + "写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_CASE,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},"
                                + "\"case_id\":{\"type\":\"integer\",\"description\":\"用例 ID\"},"
                                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                                + "\"description\":\"标签名列表（字典外的标签将被拒绝）\"}"
                                + "},\"required\":[\"project_id\",\"case_id\",\"tags\"]}"),
 // Mock family : agents self-serve mock — see spaces, create
                // stubs, publish them, then verify hits without leaving the agent.
                new McpToolDefinition("list_mock_spaces",
                        "查询项目的 Mock 空间列表（只读，走项目成员闸与 H1 隔离）：含路由 handle、"
                                + "生命周期状态（0=草稿/1=已发布/2=已退役）、规则数与命中率摘要。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"}"
                                + "},\"required\":[\"project_id\"]}"),
                new McpToolDefinition("inspect_mock_hits",
                        "查询 Mock 空间的命中账本（只读，先过空间归属项目成员闸）：一页命中记录，"
                                + "含方法/路径/是否命中规则/来源（stub|llm|forwarded）——Agent 起完 mock "
                        + "自助自证「请求确实打到了模拟面」。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"space_id\":{\"type\":\"integer\",\"description\":\"Mock 空间 ID\"},"
                                + "\"matched\":{\"type\":\"boolean\",\"description\":\"只看命中/未命中（可选=两类都要）\"},"
                                + "\"page\":{\"type\":\"integer\",\"description\":\"页码，默认 1\"},"
                                + "\"page_size\":{\"type\":\"integer\",\"description\":\"每页条数，默认 20，上限 100\"}"
                                + "},\"required\":[\"space_id\"]}"),
                new McpToolDefinition("create_mock_stub",
                        "在 Mock 空间创建一条模拟规则（须 mcp.mock 写权限）：method+path 匹配后返回指定响应；"
                                + "保存时经平台统一校验（格式不合法会被拒绝并说明原因），发布态空间下一次轮询即生效。"
                                + "写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_MOCK,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"space_id\":{\"type\":\"integer\",\"description\":\"Mock 空间 ID\"},"
                                + "\"name\":{\"type\":\"string\",\"description\":\"规则名称\"},"
                                + "\"method\":{\"type\":\"string\",\"description\":\"HTTP 方法（ANY=全方法）\"},"
                                + "\"path_pattern\":{\"type\":\"string\",\"description\":\"路径（默认精确匹配）\"},"
                                + "\"path_match_type\":{\"type\":\"string\",\"description\":\"exact|prefix|regex|template（默认 exact）\"},"
                                + "\"priority\":{\"type\":\"integer\",\"description\":\"空间内求值序（越小越先，可选）\"},"
                                + "\"response\":{\"type\":\"object\",\"description\":\"响应构造：{status, headers, body|json}（可选，缺省 200 空体）\"}"
                                + "},\"required\":[\"space_id\",\"name\",\"method\",\"path_pattern\"]}"),
                new McpToolDefinition("publish_mock_space",
                        "发布 Mock 空间（draft→published，须 mcp.mock 写权限）：只发布不改内容——生产环境绑定"
                                + "复核与状态机单边闸在平台侧执行；expected_version 缺省时读取当前行版本"
                                + "（并发冲突返回 409 并说明原因，不静默覆盖）。写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_MOCK,
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"space_id\":{\"type\":\"integer\",\"description\":\"Mock 空间 ID\"},"
                                + "\"expected_version\":{\"type\":\"integer\",\"description\":\"期望行版本（可选；缺省=读当前版本）\"}"
                                + "},\"required\":[\"space_id\"]}"),
                // Document channel (BH ruling batch): upload lands the file and enqueues
                // extraction through the same core services the in-app page uses;
                // the call returns a handle immediately and never blocks on the LLM run.
                new McpToolDefinition("doc.upload",
                        "上传需求文档并登记需求点提取任务：上传即落库并进入提取队列（与站内文档链路同一处理），同步返回 {document_id, task_id} 句柄；提取进度用 doc_task.poll 查询，本次调用不阻塞等待提取完成。超过大小上限 mcp.doc.max-bytes（默认 32MB，系统设置可调）会被拒绝并提示当前大小与上限；仅支持 PDF 与 Markdown 文档；dry_run=true 时只做大小、格式与项目可达性预检，不入库。须 mcp.doc 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_DOC,
                        "{\"type\":\"object\",\"properties\":{\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},\"name\":{\"type\":\"string\",\"description\":\"文档名称（决定解析格式，如 登录需求.pdf）\"},\"content_base64\":{\"type\":\"string\",\"description\":\"文档原件的 Base64 编码\"},\"doc_type\":{\"type\":\"string\",\"description\":\"pdf|markdown（可选，缺省按名称扩展名判定）\"},\"dry_run\":{\"type\":\"boolean\",\"description\":\"true=仅预检不入库（可选，默认 false）\"}},\"required\":[\"project_id\",\"name\",\"content_base64\"]}"),
                new McpToolDefinition("doc_task.poll",
                        "查询文档提取任务状态（只读，走项目成员隔离）：出参四形态 {state, progress, requirement_count, fidelity_summary}——state=parsing/parsed/parse_failed/extracting/extracted；requirement_count=已产出的需求点条数；fidelity_summary=忠实账摘要（注入与来源占比、锚点覆盖，提取未完成时为空）。task_id 取 doc.upload 回执中的值。",
                        false, SCOPE_DOC,
                        "{\"type\":\"object\",\"properties\":{\"task_id\":{\"type\":\"integer\",\"description\":\"doc.upload 回执中的任务号\"}},\"required\":[\"task_id\"]}"),
                new McpToolDefinition("doc_task.cancel",
                        "取消进行中的需求点提取任务：取消成功后文档回到「已解析、可再次提取」状态；任务已处于终态时幂等返回当前状态，不产生任何变更。回执 {cancelled, state}。与站内「停止提取」同一处理面。须 mcp.doc 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_DOC,
                        "{\"type\":\"object\",\"properties\":{\"task_id\":{\"type\":\"integer\",\"description\":\"doc.upload 回执中的任务号\"}},\"required\":[\"task_id\"]}"),
                // Generation trigger (BH ruling batch): rides the same batch door as the
                // in-app generation chain; the call enqueues and returns the batch handle
                // immediately, results land as pending-review cases read back via get_cases.
                new McpToolDefinition("gen.cases_from_requirement",
                        "按需求点批量触发用例生成：逐条需求走站内同一批量生成链入队，新用例落「待评审」并带来源标注，不自动采纳；同步返回 {task_id} 句柄（即批次号），生成进度与结果用 get_cases 查询，本次调用不阻塞等待生成完成。条数上限 mcp.gen.max-requirements（默认 200，系统设置可调），超限会被拒绝并提示当前条数与上限；mode=scenario（缺省）先逐条做场景枚举再入队，mode=plain 直接入队；dry_run=true 只做需求点有效性与条数预检，不入队。须 mcp.gen 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_GEN,
                        "{\"type\":\"object\",\"properties\":{\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},\"requirement_ids\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"description\":\"需求点 ID 列表（至少一条，同项目）\"},\"mode\":{\"type\":\"string\",\"description\":\"scenario|plain，缺省 scenario（先逐条场景枚举再入队）\"},\"dry_run\":{\"type\":\"boolean\",\"description\":\"true=仅预检不入队（可选，默认 false）\"}},\"required\":[\"project_id\",\"requirement_ids\"]}"),
                // In-session confirmation loop : a write tool
                // hands back a one-shot confirm ref and mcp.review.confirm settles it inside the
                // agent conversation - the human gate stays, only the channel moves.
                new McpToolDefinition("script.writeback",
                        "站外脚本写回已有用例：变更不直接落库——先登记草案并返回一次性确认码 confirm_ref（有效期见回执 expires_in_seconds，可按系统设置 mcp.confirm.ttl-seconds 调整），回执同时携带草案摘要；请在会话内调用 mcp.review.confirm 表示接受（accept）或拒绝（reject），接受后脚本才作为新版本落库、用例转「待评审」，拒绝则草案作废、零变更，全程无需登录平台界面（平台草案卡为并行兜底通道，同一草案两通道先到先得）。带 evidence 证据包（ran_at/env_fingerprint/passed/failed/log_sha256）时按站外实证分级，缺省按站外自报分级；已评审通过状态的使用例会拒绝写回并提示出路；expected_version 可选，用于避免覆盖他人在途版本。须 mcp.script 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_SCRIPT,
                        "{\"type\":\"object\",\"properties\":{\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},\"case_id\":{\"type\":\"integer\",\"description\":\"目标用例 ID\"},\"script\":{\"type\":\"string\",\"description\":\"动作 JSON Schema v2 脚本正文\"},\"schema_version\":{\"type\":\"string\",\"description\":\"v1|v2（可选，默认 v2）\"},\"expected_version\":{\"type\":\"integer\",\"description\":\"期望脚本版本（可选，缺省=不比较，陈旧=版本冲突并提示当前版本）\"},\"evidence\":{\"type\":\"object\",\"description\":\"证据包（可选）：{ran_at, env_fingerprint, passed, failed, log_sha256}，log_sha256 为本地运行日志的 SHA-256 摘要\"}},\"required\":[\"project_id\",\"case_id\",\"script\"]}"),
                new McpToolDefinition("mcp.review.confirm",
                        "对写型工具返回的待确认草案表示接受或拒绝：confirm_ref 取写型工具回执中的一次性确认码，decision=accept 执行草案落库，decision=reject 作废草案零变更（拒绝同样给出确认结果并进审计）。确认码被处理一次后立即失效，第二次出示会被拒绝并提示重新发起写操作；超过有效期自动作废。全程在会话内完成，无需登录平台界面；平台草案卡为兜底通道，同一草案两通道先到先得。须 mcp.review 权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_REVIEW,
                        "{\"type\":\"object\",\"properties\":{\"confirm_ref\":{\"type\":\"string\",\"description\":\"写型工具回执中的一次性确认码\"},\"decision\":{\"type\":\"string\",\"description\":\"accept=接受并落库 | reject=拒绝并作废\"},\"reason\":{\"type\":\"string\",\"description\":\"决定理由（可选，进审计供追溯）\"}},\"required\":[\"confirm_ref\",\"decision\"]}"),
                // Execution channel: the batch trigger is the same core door the
                // in-app batch page and /open/ci ride (create draft batch, then
                // start) — an agent-started run is indistinguishable from a
                // human-started one and no second execution path exists here.
                // suite_id = the regression suite page, whose own case list is
                // the selection; reads resolve the owning project from the batch
                // row so a guessed id never reveals another project's data.
                new McpToolDefinition("exec.run",
                        "触发用例执行：按用例集（selection.suite_id）或用例清单（selection.case_ids）建批并启动，与站内批次页和 CI 通道走同一处理件，不另开执行路径；同步返回 {batch_id, case_count, env_id, status_label, queue_pos, estimated_start_at}，本次调用不阻塞等待执行完成——进度用 exec.status 查询，结果摘要用 report.fetch 查询，误发可用 exec.cancel 停止未派发任务（已完成的执行结果保留）。dry_run=true 时只预检执行范围与环境归属，不建批不启动；用例集或环境不属于该项目、执行范围为空、以及 suite_id 与 case_ids 同时给出时都会被拒绝并说明原因。须 mcp.exec 写权限，写操作必须携带 Idempotency-Key（同键重放返回首次结果，不重复建批）。",
                        true, SCOPE_EXEC,
                        "{\"type\":\"object\",\"properties\":{\"project_id\":{\"type\":\"integer\",\"description\":\"项目 ID\"},\"selection\":{\"type\":\"object\",\"description\":\"执行范围：suite_id 与 case_ids 二选一，必须给出一项\",\"properties\":{\"suite_id\":{\"type\":\"integer\",\"description\":\"用例集 ID（按该用例集当前用例清单执行，站内「用例集」页可见）\"},\"case_ids\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"description\":\"用例 ID 列表（至少一条，须属该项目）\"}},\"required\":[]},\"env_id\":{\"type\":\"integer\",\"description\":\"执行环境 ID（可选；用 selection.suite_id 时缺省沿用该用例集绑定的执行环境，用 selection.case_ids 时必须给出）。无法确定执行环境时调用被拒绝，且不创建批次、不消耗幂等键（环境可在站内「项目 - 执行环境」页查看）\"},\"name\":{\"type\":\"string\",\"description\":\"批次名称（可选，缺省=「站外智能体触发批次」加时间）\"},\"dry_run\":{\"type\":\"boolean\",\"description\":\"true=仅预检不入队不启动（可选，默认 false）\"}},\"required\":[\"project_id\",\"selection\"]}"),
                new McpToolDefinition("exec.status",
                        "查询批次执行进度（只读）：先按批次归属项目校验当前身份的可达范围，再返回 {batch_id, name, status, status_label, case_count, queue_pos, env_id, env_name, started_at, finished_at}；状态中文名与计数直读平台批次口径，本通道不重算。批次不存在或不属于可见项目时会被拒绝并说明原因；结果摘要请用 report.fetch。",
                        false, SCOPE_EXEC,
                        "{\"type\":\"object\",\"properties\":{\"batch_id\":{\"type\":\"integer\",\"description\":\"exec.run 回执中的批次号\"}},\"required\":[\"batch_id\"]}"),
                new McpToolDefinition("exec.cancel",
                        "停止已受理的批次：与站内「取消批次」同一处理件（不另开停止路径）——待执行/环境不可用/执行中/已暂停的批次取消后，未派发任务出队、已完成的执行结果保留；已完成/部分失败/已取消等终态幂等返回当前状态且未产生任何变更（回执 cancelled=false）；草稿批次由平台状态机拒绝并说明当前状态。回执 {cancelled, batch_id, state, state_label, message}。须 mcp.exec 写权限，写操作须携带幂等键参数 idempotency_key（不能自定义请求头的客户端用它即可，等价通道=HTTP 头 Idempotency-Key），同键重复调用返回首次结果、不重复执行。",
                        true, SCOPE_EXEC,
                        "{\"type\":\"object\",\"properties\":{\"batch_id\":{\"type\":\"integer\",\"description\":\"exec.run 回执中的批次号\"}},\"required\":[\"batch_id\"]}"),
                new McpToolDefinition("report.fetch",
                        "获取批次结果摘要（只读）：先按批次归属项目校验当前身份的可达范围，再给出摘要句与计数——通过率直读平台徽章同源口径，通过/失败/跳过/超时计数与失败分类分布直读批次收口快照，本通道不重算任何比率；出参不含逐条用例明细（明细在站内报告页或按用例筛选），禁整包结果倾倒。批次尚未收口时返回当前进度、已完成部分的通过率与提示（用 exec.status 轮询），不返回空报告。",
                        false, SCOPE_EXEC,
                        "{\"type\":\"object\",\"properties\":{\"batch_id\":{\"type\":\"integer\",\"description\":\"exec.run 回执中的批次号\"}},\"required\":[\"batch_id\"]}"),
                new McpToolDefinition(TOOL_TOKENS_USAGE,
                        "查询本次调用所出示 API Token 的用量账（只读，无参数）：调用次数、失败次数、"
                                + "p95 延迟毫秒——数字与平台调用留账实时一致，不另计一套；尚无一笔带耗时样本的"
                                + "调用时 p95 为 null。账目只覆盖本令牌，不含其他 Token 的数据。",
                        false, SCOPE_READ,
                        "{\"type\":\"object\",\"properties\":{},\"required\":[]}")).sorted(java.util.Comparator.comparing(McpToolDefinition::name))
                .toList();
    }

    
    public List<McpToolDefinition> all() {
        return tools;
    }

    
    public Optional<McpToolDefinition> byName(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
