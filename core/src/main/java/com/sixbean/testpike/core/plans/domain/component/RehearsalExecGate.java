package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Three-facet AND gate for launching a regression-rehearsal batch straight
 * from the conversation (AO batch , contract = section three of
 * the AO adjudication sheet):
 * the dialog execution master switch AND the per-application-point rehearsal
 * sub switch AND the project whitelist must all be open, evaluated coarse to
 * fine so a refusal always names the FIRST closed facet.
 * <p>Key posture: the master switch reuses
 * {@link ToolAccessGate#KEY_EXEC_ENABLED} (its own single source, factory
 * default on); the sub switch {@code ai.chat.rehearsal-run.enabled} ships
 * DEFAULT OFF because starting an execution batch is a production-change
 * action — the documented exemption class of the AI-features-default-open
 * rule (reason registered in the V336 seed comment); the whitelist
 * {@code ai.chat.exec-project-whitelist} is a comma-separated project id
 * list, default EMPTY = nobody may launch (fail-closed).
 * <p>Refusal sentences always name where to change the setting (the literal
 * settings path) plus the literal config key plus the consequence (honest
 * guidance rule); every value is re-read per call so hot updates take effect
 * without a restart. The allow path returns one declaration line per facet —
 * the release-before-declare duty the receipt card carries.
 */
@Component
public class RehearsalExecGate {

    /** Comma-separated launchable project ids (empty = refuse every project). */
    public static final String KEY_WHITELIST = "ai.chat.exec-project-whitelist";

    /** Per-application-point rehearsal launch sub switch (factory default off). */
    public static final String KEY_REHEARSAL_RUN = "ai.chat.rehearsal-run.enabled";

    private final ConfigService configService;

    public RehearsalExecGate(ConfigService configService) {
        this.configService = configService;
    }

    /**
 * Judge the three facets for one project segment; returns the three
 * declaration lines (each names its key) when allowed, and throws a
 * refusal that names the first closed facet, its key, the settings path
 * and the consequence otherwise. Refusal happens strictly BEFORE any
 * launch side effect, so a rejected run leaves the batch untouched.
 */
    public List<String> requireAllowed(long projectId) {
        if (!switchOn(ToolAccessGate.KEY_EXEC_ENABLED, "true")) {
            throw new ServiceException("演练批次未启动：对话执行类总闸 ai.chat.exec-enabled 当前为关闭——"
                    + "可在「系统设置」中打开该参数后重试；未打开时批次保持原状态，不会进入执行队列",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!switchOn(KEY_REHEARSAL_RUN, "false")) {
            throw new ServiceException("演练批次未启动：回归演练执行开关 " + KEY_REHEARSAL_RUN
                    + " 当前为关闭（出厂默认）——可在「系统设置」中打开该参数后重试；"
                    + "未打开时演练草案仍可创建和确认，但批次不会开始执行", BizErrorCode.VALIDATION_ERROR);
        }
        Set<Long> whitelist = parseWhitelist(configService.getValue(KEY_WHITELIST, ""));
        if (whitelist.isEmpty()) {
            throw new ServiceException("演练批次未启动：执行项目白名单 " + KEY_WHITELIST
                    + " 当前为空——需先在「系统设置」中把本项目编号加入该列表；列表为空时任何项目都不允许从对话启动执行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!whitelist.contains(projectId)) {
            throw new ServiceException("演练批次未启动：项目 " + projectId + " 不在执行项目白名单 "
                    + KEY_WHITELIST + " 中——可在「系统设置」中加入该编号后重试；未加入时该项目批次保持原状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return List.of(
                "对话执行类总闸 ai.chat.exec-enabled 为开启",
                "项目 " + projectId + " 在执行项目白名单 " + KEY_WHITELIST + " 内",
                "回归演练执行开关 " + KEY_REHEARSAL_RUN + " 为开启，批次启动后进入执行队列");
    }

    /** Anything but true counts as off (fail-closed reader, hot re-read per call). */
    private boolean switchOn(String key, String factoryDefault) {
        return "true".equalsIgnoreCase(configService.getValue(key, factoryDefault));
    }

    /**
     * Parse the CSV whitelist: blank/null and unparsable garbage yield an
     * empty set (fail-closed), malformed tokens inside a list are ignored
     * while legal positive ids are kept.
     */
    public static Set<Long> parseWhitelist(String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String token : csv.split(",")) {
            try {
                long parsed = Long.parseLong(token.trim());
                if (parsed > 0) {
                    ids.add(parsed);
                }
            } catch (NumberFormatException ignored) {
                // A stray token never poisons the whole list; the garbage-only
                // case collapses to an empty set and the gate refuses.
            }
        }
        return ids;
    }
}
