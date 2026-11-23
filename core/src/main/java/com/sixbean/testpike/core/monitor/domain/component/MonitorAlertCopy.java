package com.sixbean.testpike.core.monitor.domain.component;

import java.util.regex.Pattern;


public final class MonitorAlertCopy {

    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|api[-_ ]?key|access[-_ ]?key)(\\s*[=:]\\s*)\\S+");
    private static final Pattern ACCOUNT_VALUE = Pattern.compile(
            "(?i)\\b(username|user\\.name|account)(\\s*[=:]\\s*)(\\S)");

    private MonitorAlertCopy() {
    }

    
    public static String maskHuman(String raw) {
        if (raw == null) {
            return null;
        }
        String masked = IPV4.matcher(raw).replaceAll("$1.*.*");
        masked = SECRET_VALUE.matcher(masked).replaceAll("$1$2******");
        masked = ACCOUNT_VALUE.matcher(masked).replaceAll("$1$2$3***");
        return masked;
    }

    
    public static String downTitle(String targetName) {
        return "监控告警：目标不可用「" + targetName + "」";
    }

    
    public static String downContent(String projectName, String targetName, String cause, int failures) {
        return "项目「" + projectName + "」的监控目标「" + targetName + "」连续 " + failures
                + " 次探测失败：「" + maskHuman(cause) + "」。相关环境的业务可能已中断，"
                + "请尽快人工确认；持续失败期间探测已自动降频，恢复后会自动通知。";
    }

 
    public static String flappingContent(String projectName, String targetName, int episodeCount, int windowMinutes) {
        return "项目「" + projectName + "」的监控目标「" + targetName + "」在 " + windowMinutes
                + " 分钟内第 " + episodeCount + " 次进入不可用——间歇性不可用，建议排查负载均衡"
                + "（个别节点故障/健康检查配置）或依赖服务抖动。";
    }

    
    public static String recoveredContent(String targetName) {
        return "监控目标「" + targetName + "」探测已连续成功，已恢复正常，可继续执行批次。";
    }

    
    public static String lostContent(int stallSeconds) {
        return "监控中心探测调度已 " + stallSeconds + " 秒未心跳（超过基准间隔×倍率的失联阈值），"
                + "所有环境探测可能已停摆，请尽快检查 core 服务与调度任务；本告警在心跳恢复后可再次触发。";
    }
}
