package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.judge.api.dto.DispatchReceipt;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.domain.service.JudgeDispatchService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class JudgeDispatchServiceImpl implements JudgeDispatchService {

    private static final Logger log = LoggerFactory.getLogger(JudgeDispatchServiceImpl.class);

    
    static final String KEY_INTERNAL_PROVIDERS = "judge.llm.internal-providers";

    
    static final String KEY_ALLOW_SAME_PROVIDER = "judge.llm.allow-same-provider";

    private final JudgeRunService runService;
    private final JudgeSampleService sampleService;
    private final LlmConfigService llmConfigService;
    private final ConfigService configService;
    private final AiGenPublisher aiGenPublisher;

    public JudgeDispatchServiceImpl(JudgeRunService runService,
                                    JudgeSampleService sampleService,
                                    LlmConfigService llmConfigService,
                                    ConfigService configService,
                                    AiGenPublisher aiGenPublisher) {
        this.runService = runService;
        this.sampleService = sampleService;
        this.llmConfigService = llmConfigService;
        this.configService = configService;
        this.aiGenPublisher = aiGenPublisher;
    }

    @Override
    public DispatchReceipt dispatch(long runId, String evaluatedProvider) {
        RunView run = runService.detail(runId);
        if (run == null) {
            throw new ValidationException("评测运行不存在（id=" + runId + "）");
        }
        if (run.status() == null || run.status() != 0) {
            throw new ValidationException("评测运行当前状态不允许派发裁判请求——仅待跑状态"
                    + "可派发（重复派发会造成样本双评污染对账，id=" + runId + "）");
        }
        if (run.llmConfigId() == null) {
            throw new ValidationException("该评测运行未配置 LLM 裁判模型（llm_config_id"
                    + " 为空）——纯规则评分无需派发裁判请求，请直接查看评分记录");
        }
        LlmConfigInfo judge = llmConfigService.get(run.llmConfigId());
        if (judge == null) {
            throw new ValidationException("裁判模型配置不存在（id=" + run.llmConfigId()
                    + "）——可能已被删除，请在模型清单重新选用后新建评测运行");
        }
        if (judge.status() != 1) {
            throw new ValidationException("裁判模型「" + judge.name() + "」已停用，不可执行"
                    + "评测（IT-K9-01 同口径）——请启用该模型或改用其他模型");
        }

        List<SampleView> samples = sampleService.listSamples(run.evalsetName(),
                run.evalsetVersion());
        guardSensitiveOutbound(samples, judge);
        guardSameProvider(run, judge, evaluatedProvider);

        runService.start(runId);
        for (SampleView sample : samples) {
            aiGenPublisher.publishJudgeSample(new JudgeSampleMessage(runId,
                    sample.id(), judge.id(), run.evalsetDigest()));
        }
        log.info("LLM 裁判派发完成 runId={} 样本数={} judgeModel={} provider={}",
                runId, samples.size(), judge.name(), judge.provider());
        return new DispatchReceipt(samples.size(), judge.provider());
    }

 
    private void guardSensitiveOutbound(List<SampleView> samples, LlmConfigInfo judge) {
        boolean hasSensitive = samples.stream()
                .anyMatch(s -> s.sensitive() != null && s.sensitive() == 1);
        if (!hasSensitive) {
            return;
        }
        if (isInternalProvider(judge.provider())) {
            return;
        }
        long n = samples.stream()
                .filter(s -> s.sensitive() != null && s.sensitive() == 1).count();
        throw new ValidationException("评测集含敏感样本 " + n + " 条（sensitive=1），裁判模型"
                + "「" + judge.name() + "」厂商 " + judge.provider() + " 不在内部部署白名单"
                + "（键 " + KEY_INTERNAL_PROVIDERS + "）——脱敏未过审样本禁送外部厂商裁判"
                + "；请改用内部模型，或先完成脱敏过审");
    }

 
    private void guardSameProvider(RunView run, LlmConfigInfo judge, String evaluatedProvider) {
        if (evaluatedProvider == null || evaluatedProvider.isBlank()
                || !evaluatedProvider.trim().equalsIgnoreCase(judge.provider())) {
            return;
        }
        String exempt = configService.getValue(KEY_ALLOW_SAME_PROVIDER, "0");
        if (!"1".equals(exempt.trim())) {
            throw new ValidationException("裁判模型与被评目标同为厂商 " + judge.provider()
                    + "——同厂商默认禁配（自我偏置防线）；确需豁免请置键 "
                    + KEY_ALLOW_SAME_PROVIDER + "=1（豁免生效将留派发日志痕）");
        }
        log.warn("同厂商禁配豁免生效 runId={} provider={}（键 {}=1 留痕）",
                run.id(), judge.provider(), KEY_ALLOW_SAME_PROVIDER);
    }

    private boolean isInternalProvider(String provider) {
        String raw = configService.getValue(KEY_INTERNAL_PROVIDERS, "");
        if (raw == null || raw.isBlank() || provider == null) {
            return false;
        }
        Set<String> list = Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return list.contains(provider.trim().toLowerCase(Locale.ROOT));
    }
}
