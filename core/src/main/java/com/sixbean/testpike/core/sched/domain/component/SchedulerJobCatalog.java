package com.sixbean.testpike.core.sched.domain.component;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class SchedulerJobCatalog {

    private final Map<String, String> descriptions;

    public SchedulerJobCatalog() {
        descriptions = new LinkedHashMap<>();
        descriptions.put("dailyDigestScan", "每日用例数据摘要统计");
        descriptions.put("flakyDetectionDaily", "每日不稳定用例（Flaky）检测");
        descriptions.put("reviewRemindHourly", "每小时评审超时提醒");
        descriptions.put("costOverrideExpire", "成本额度临时调整到期清理");
        descriptions.put("executorOfflineSweep", "执行器离线状态扫描");
        descriptions.put("execTimeoutWatchdog", "执行任务超时看门狗");
        descriptions.put("envHealthScan", "测试环境健康巡检");
        descriptions.put("certExpiryScan", "环境证书到期扫描提醒");
        descriptions.put("reportWeeklyGenerate", "每周测试报告生成");
        descriptions.put("reportMonthlyGenerate", "每月测试报告生成");
        descriptions.put("reportRetentionDaily", "报告留存期每日清理");
        descriptions.put("retentionCleanupDaily", "数据留存策略每日清理");
        descriptions.put("promptAdoptionSummaryDaily", "每日提示词采纳效果汇总");
        descriptions.put("scheduleTriggerScan", "每分钟定时触发扫描（回归计划/批次模板/定时压测场景）");
        descriptions.put("specSubscriptionScan", "接口契约订阅到期扫描（定时拉取变更）");
        descriptions.put("monitorScan", "监控目标到期扫描与探测");
        descriptions.put("monitorLostWatchdog", "监控中心停摆失联自告警");
        descriptions.put("monitorEnvVersionRecheck", "环境档案变更监控退避复位");
        descriptions.put("appautoLeaseSweep", "APP 设备租约过期周期回收");
        descriptions.put("appautoDeviceProbe", "远程 ADB 设备柜周期探活与失联隔离");
        descriptions.put("appautoFlakySentinelDaily", "APP 用例×设备稳定性分夜间批扫描");
        descriptions.put("appautoReservationSweep", "APP 测试资源预约到期清扫与自动释放");
        descriptions.put("clusterVersionBaselineScan", "部件版本基准扫描（描述符对账）");
        descriptions.put("clusterUpstreamProbe", "上游引擎金版本探测回写");
        descriptions.put("upgradePipelineAdvance", "升级流水线推进（灰度派发/回执轮询/套件验证/分批冷却）");
        descriptions.put("clusterReleaseCheck", "平台发版源候选探测（新版本进待确认清单，不自动发布）");
        descriptions.put("mockSpaceExpirySweep", "临时 Mock 空间到期清扫（退役/删除，有引用则顺延并通知负责人）");
        descriptions.put("mockHitSweep", "Mock 命中流水保留清理（按保留天数删除超窗检视流水）");
        descriptions.put("knowledgeReconcileDaily", "知识库向量每日对账（AI 服务）");
        descriptions.put("usageCalibrationRefresh", "用量计费校准刷新（AI 服务）");
        descriptions.put("docExtractStallSweep", "文档提取停滞清扫（提取中长时间无回写的文档恢复为可重新提取）");
        descriptions.put("mcpKnowledgeSyncScan", "MCP 知识库同步订阅到期扫描（只读工具定时拉入知识台账）");
        descriptions.put("flakyProbeSweep", "被隔离用例回探台账（连续通过后提示可解除隔离，需人工确认，系统不自动解除）");
        descriptions.put("aiCtxDistillDaily", "项目记忆每日归纳（评审驳回、修复采纳、对话点踩、工具拒答四类反馈增量）");
        descriptions.put("apiAccessCatalogRefresh", "接口目录周期重建（对话与对外面按目录调用本平台自身接口，本任务让新上线的接口无需重启即可被看到）");
        descriptions.put("dlqAutoReplay", "死信队列自动补投回路（白名单失败类型且上游恢复后补回原队列，轮次耗尽自动作废并通知管理员）");
        descriptions.put("loginStateRetentionSweep", "登录态失效条目每日保留清理（超出保留天数的失效账本行删除，采集产生的无引用密文一并清理）");
        descriptions.put("changeLinkageReconcileDaily", "上下游联动边账每日对账（外键与联动边登记的差异每日盘一次，只出账报告不自动改数据）");
        descriptions.put("linkageUrgeHourly", "上下游联动待处置催办与超时升级（超过时限提醒执行人，再超时升级项目负责人；阈值在系统设置可调，0=关闭该级）");
        descriptions.put("webElementLedgerRetentionDaily", "页面元素库留存双回路（超期未验证候选自动退役；退役到期条目连锚点统计一并清理，天数在系统设置 element.candidate-ttl-days / element.retention-days 可调）");
    }

    
    public Map<String, String> descriptions() {
        return java.util.Collections.unmodifiableMap(descriptions);
    }

    
    public String describe(String jobName) {
        return descriptions.getOrDefault(jobName, jobName);
    }
}
