package com.sixbean.testpike.core.mock.domain.component;

/**
 * Single-source refusal wording for the mock runtime doors under the R63
 * smart-guidance contract: opening or sealing a recording box, promoting a
 * live hit into a stub, landing hit traffic into the ledger, and storing or
 * serving the AI fallback answer. Every user-reachable refusal raised by
 * those flows is phrased here so one L2 test can pin the three elements
 * (offending value echoed, on-screen repair entry quoted, action word for
 * the next step) without booting infrastructure, and so the same sentence
 * never lives in two call sites.
 *
 * <p>The quoted literals must match the shipped frontend wording: the
 * recording page with its open-box dialog (upstream base address), the
 * inspector page with its promote action, the simulation-space page, the
 * dead-letter page and the system-configs page. Keep them in sync when the
 * screens change.
 */
public final class MockRuntimeRefusals {

    /** Repair entry quoted for every recording-box door. */
    public static final String RECORDING_ENTRY = "「录制回放」页";

    /** Repair entry quoted for hit rows and their promotion refusals. */
    public static final String INSPECTOR_ENTRY = "「流量检视」页";

    /** Repair entry quoted for space-level attributions. */
    public static final String SPACE_ENTRY = "「仿真空间」页";

    /** Repair entry quoted for batches the ledger refused to land. */
    public static final String DEAD_LETTER_ENTRY = "「死信队列」页";

    /** Repair entry quoted for the dual AI-fallback switches. */
    public static final String CONFIGS_ENTRY = "「系统配置」页";

    /** On-screen label of the promote action on a hit row. */
    public static final String PROMOTE_ACTION = "「把这条请求变成 Mock 规则」";

    private MockRuntimeRefusals() {
    }

    public static String recordingSpaceNotPublished(long spaceId) {
        return "你提交的 Mock 空间（ID=" + spaceId + "）不是已发布状态——未发布或已退役的空间不对外服务，"
                + "没有可录制的流量，开录音盒被拒绝；请到" + SPACE_ENTRY + "把该行点「发布空间」，再回到"
                + RECORDING_ENTRY + "重新开盒";
    }

    public static String recordingTargetBlank() {
        return "「上游基址」留空——代理没有可转发的目标地址，开录音盒被拒绝；请在" + RECORDING_ENTRY
                + "的「开录音盒」表单里填写「上游基址」（示例 http://svc.test/api）后重新提交";
    }

    public static String recordingTargetIllegal(String target) {
        return "「上游基址」填的「" + target + "」不是合法的 http/https 地址（缺协议头或主机名），"
                + "代理无法解析转发目标；请在" + RECORDING_ENTRY + "的「开录音盒」表单里把「上游基址」"
                + "改成合法地址（示例 http://svc.test/api）后重新提交";
    }

    public static String recordingBoxAlreadyOpen(long spaceId) {
        return "该空间（ID=" + spaceId + "）已有一个「录制中」的录音盒，同一空间同时只允许开一个盒；"
                + "请到" + RECORDING_ENTRY + "对进行中的那一行先点「封盒」收口本批流量，再开新盒";
    }

    public static String recordingBoxAlreadySealed(long recordingId, String status) {
        return "录音盒（ID=" + recordingId + "）当前状态是 " + status + "，不在录制中，「封盒」已经没有可停的盒；"
                + "需要再录一批流量请到" + RECORDING_ENTRY + "对该空间重新点「开录音盒」";
    }

    public static String recordingBoxMissing(long recordingId) {
        return "找不到 ID=" + recordingId + " 的录音盒，可能已被删除或超出流水保留期；请到" + RECORDING_ENTRY
                + "重新选择空间核对录音盒清单，该行不在了就重新「开录音盒」开始一批新录制";
    }

    public static String hitSpaceMismatch(long hitId, Long ownerSpaceId, long selectedSpaceId) {
        return "命中记录（ID=" + hitId + "）属于空间 " + ownerSpaceId + "，与你选择的空间 " + selectedSpaceId
                + " 不一致，跨空间固化会把别的空间的流量写进当前空间；请在" + INSPECTOR_ENTRY
                + "切换到该记录所属的空间后再点" + PROMOTE_ACTION;
    }

    public static String hitNoResponseToPromote(long hitId) {
        return "命中记录（ID=" + hitId + "）没有录到应答（无响应面记录，多为转发失败的传输错误行）——"
                + "没有真实录到的响应不能凭空编成规则；请在" + INSPECTOR_ENTRY + "挑一条带状态码的请求再点"
                + PROMOTE_ACTION;
    }

    public static String hitRowMissing(long hitId) {
        return "命中记录（ID=" + hitId + "）不存在或已超出保留期被清理，无法转成规则；请到" + INSPECTOR_ENTRY
                + "重新选择空间和时间范围核对该行是否仍在，已不在请在" + RECORDING_ENTRY
                + "重新录制一批流量后再固化";
    }

    public static String hitLedgerSpaceKeyBlank() {
        return "这批量上报的命中流量没有带空间路由标识（spaceKey），账本无法判断该记到哪个空间，"
                + "整批已拒收并进死信留痕，不会静默丢弃；请检查上报方的 spaceKey 配置补齐后重新上报，"
                + "被拒明细可在" + DEAD_LETTER_ENTRY + "查看原因";
    }

    public static String hitLedgerSpaceKeyUnknown(String spaceKey) {
        return "命中流量指向的空间路由标识「" + spaceKey + "」查无对应空间（可能已被删除），"
                + "整批已拒收并进死信留痕，不会静默丢弃；请到" + SPACE_ENTRY
                + "确认该标识的空间是否仍在，不在请把上报方的 spaceKey 改成现存空间的标识后重新上报";
    }

    public static String fallbackSpaceIdMissing() {
        return "AI 兜底应答入库请求缺少 spaceId——缓存按空间隔离，没有空间无法归属，已拒绝入库；"
                + "请到「仿真空间」页核对空间 ID，由调用方补上 spaceId 后重新提交";
    }

    public static String fallbackStatusOutOfRange(int status) {
        return "AI 生成的应答 HTTP 状态 " + status + " 不在合法区间 100..599，存入缓存后将来无法作为合法状态回放，"
                + "已拒绝入库；请生成链路把「HTTP 状态」改成合法状态码（示例 200 或 503）后重新提交";
    }

    public static String fallbackBodyNull() {
        return "AI 生成的应答缺少 body 字段（值为 null）——空正文应当以空字符串表达，缺字段说明生成链路产物不完整，"
                + "已拒绝入库；请生成链路补齐「body」字段为完整载荷（示例 \"body\": \"\"）后重新提交";
    }

    public static String fallbackSnapshotUnserializable(String reason) {
        return "AI 生成的应答快照无法序列化为 JSON，已拒绝入库；原因：" + reason
                + "；请生成链路检查「响应快照」载荷里是否混入无法转 JSON 的内容（例如循环引用的对象），修正后重新提交";
    }

    public static String fallbackGateClosed(String label, String key) {
        return "「" + label + "」当前为关闭状态（配置项 " + key + "）——AI 兜底应答受总闸 ai.mock.enabled 与"
                + "分开关 ai.mock.llm-fallback 双重控制，任一关闭都不会生成或入库应答；"
                + "需要该能力请在" + CONFIGS_ENTRY + "把对应开关改为开启后重试";
    }
}
