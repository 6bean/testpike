package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.judge.api.dto.DispatchReceipt;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.domain.service.JudgeDispatchService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.domain.service.JudgeSampleService;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInfo;
import com.sixbean.testpike.core.llmconfig.api.dto.LlmConfigInput;
import com.sixbean.testpike.core.llmconfig.domain.service.LlmConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeDispatchTest {

    @Autowired
    private JudgeDispatchService judgeDispatchService;

    @Autowired
    private JudgeRunService judgeRunService;

    @Autowired
    private JudgeRubricService judgeRubricService;

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private LlmConfigService llmConfigService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j3%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j3%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j3s.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j3k.%'");
        jdbcTemplate.update("DELETE FROM tp_llm_config WHERE name LIKE 't278j3m.%'");
        resetKey("judge.llm.internal-providers", "");
        resetKey("judge.llm.allow-same-provider", "0");
    }

    @AfterEach
    void restoreKeys() {
        resetKey("judge.llm.internal-providers", "");
        resetKey("judge.llm.allow-same-provider", "0");
    }

    private void resetKey(String key, String value) {
        configService.list(null).stream().filter(c -> key.equals(c.configKey())).findFirst()
                .ifPresent(c -> configService.update(9301L, c.id(),
                        new ConfigInput(c.configKey(), value, c.groupName(),
                                c.valueType(), c.encryptFlag(), c.description())));
    }

    private RubricView newRubric(String name) {
        return judgeRubricService.save(new RubricSaveInput(name, "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 1.0,
                        List.of("9 分长这样：直接命中需求", "3 分长这样：跑题"))),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9301L));
    }

    private EvalSetVersionView newSet(String setName, int sensitive) {
        return judgeSampleService.publish(setName, List.of(
                new SampleInput("需求A", "用例A", List.of("参考件1"), "manual", sensitive),
                new SampleInput("需求B", "用例B", List.of(), "manual", sensitive)), 9301L);
    }

    private LlmConfigInfo newModel(String name, String provider) {
        return llmConfigService.create(9301L, new LlmConfigInput(name, provider,
                provider + "-judge-v1", "sk-t278j3-fixture", "http://localhost:18084/v1",
                1, 0, "派发夹具模型"));
    }

    private RunView newRun(Long llmConfigId, String setName, int version, String ref) {
        RubricView rubric = newRubric("t278j3k." + ref.replace("t278j3", ""));
        return judgeRunService.createRun(new RunCreateInput(rubric.id(), setName, version,
                null, "pipeline", ref, llmConfigId, 9301L));
    }

    
    @Test
    void dispatch_待跑运行_逐样本达临时队列载荷完整并迁跑批中() {
        String queueName = "test.ai-judge." + UUID.randomUUID().toString().substring(0, 8);
        amqpAdmin.declareQueue(new Queue(queueName, false, true, false));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_JUDGE_SAMPLE, null));
        try {
            EvalSetVersionView set = newSet("t278j3s.ok", 0);
            LlmConfigInfo judge = newModel("t278j3m.ok", "internal-judge");
            RunView run = newRun(judge.id(), set.setName(), set.version(), "t278j3-ok");

            DispatchReceipt receipt = judgeDispatchService.dispatch(run.id(), "qwen");

            assertEquals(2, receipt.dispatched(), "两样本=两条裁判请求");
            assertEquals("internal-judge", receipt.judgeProvider());
            for (int i = 0; i < 2; i++) {
                var received = rabbitTemplate.receive(queueName, 5000);
                assertNotNull(received, "第 " + (i + 1) + " 条 ai.judge.sample 未到达");
                JudgeSampleMessage msg =
                        (JudgeSampleMessage) caseEventMessageConverter.fromMessage(received);
                assertEquals(run.id(), msg.runId());
                assertNotNull(msg.sampleId());
                assertEquals(judge.id(), msg.llmConfigId());
                assertEquals(run.evalsetDigest(), msg.evalsetDigest(),
                        "评测集快照锚随行=归因防串（详设 §6.5）");
            }
            assertEquals(1, judgeRunService.detail(run.id()).status(),
                    "派发后 Run=跑批中");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void dispatch_未配模型与停用模型_人话拒() {
        EvalSetVersionView set = newSet("t278j3s.nocfg", 0);

        RunView noModel = newRun(null, set.setName(), set.version(), "t278j3-nocfg");
        ValidationException e1 = assertThrows(ValidationException.class,
                () -> judgeDispatchService.dispatch(noModel.id(), "qwen"));
        assertTrue(e1.getMessage().contains("未配置 LLM 裁判模型"),
                "纯规则运行不该派发裁判请求：" + e1.getMessage());

        LlmConfigInfo stopped = llmConfigService.create(9301L, new LlmConfigInput(
                "t278j3m.stopped", "internal-judge", "judge-v2", "sk-t278j3-fixture",
                "http://localhost:18084/v1", 0, 0, "停用夹具模型"));
        RunView offRun = newRun(stopped.id(), set.setName(), set.version(), "t278j3-off");
        ValidationException e2 = assertThrows(ValidationException.class,
                () -> judgeDispatchService.dispatch(offRun.id(), "qwen"));
        assertTrue(e2.getMessage().contains("停用"),
                "停用模型不可执行评测（IT-K9-01 同口径）：" + e2.getMessage());
        assertEquals(0, judgeRunService.detail(offRun.id()).status(),
                "闸拒后 Run 保持待跑（禁半途进跑批中）");
    }

 
    @Test
    void dispatch_敏感样本禁送外部厂商_内部白名单放行() {
        EvalSetVersionView set = newSet("t278j3s.sens", 1);
        LlmConfigInfo external = newModel("t278j3m.external", "openai");
        RunView run = newRun(external.id(), set.setName(), set.version(), "t278j3-sens");

        ValidationException e = assertThrows(ValidationException.class,
                () -> judgeDispatchService.dispatch(run.id(), "qwen"));
        assertTrue(e.getMessage().contains("敏感") && e.getMessage().contains("外部厂商"),
                "sensitive 外送须人话点名红线：" + e.getMessage());
        assertEquals(0, judgeRunService.detail(run.id()).status());

        LlmConfigInfo internal = newModel("t278j3m.internal2", "vendor-x");
        RunView run2 = newRun(internal.id(), set.setName(), set.version(), "t278j3-sens2");
        resetKey("judge.llm.internal-providers", "vendor-x, vendor-y");
        assertEquals(2, judgeDispatchService.dispatch(run2.id(), "qwen").dispatched(),
                "白名单命中内部厂商即放行（键值逗号分隔忽略大小写）");
    }

    
    @Test
    void dispatch_同厂商默认禁_豁免键放行() {
        EvalSetVersionView set = newSet("t278j3s.same", 0);
        LlmConfigInfo judge = newModel("t278j3m.same", "qwen");
        RunView run = newRun(judge.id(), set.setName(), set.version(), "t278j3-same");

        ValidationException e = assertThrows(ValidationException.class,
                () -> judgeDispatchService.dispatch(run.id(), "QWEN"));
        assertTrue(e.getMessage().contains("同厂商"),
                "自我偏置防线默认禁（大小写不敏感比对）：" + e.getMessage());

        resetKey("judge.llm.allow-same-provider", "1");
        assertEquals(2, judgeDispatchService.dispatch(run.id(), "qwen").dispatched(),
                "配置可豁免留痕（详设 §6.4）");
    }

    
    @Test
    void dispatch_重复派发_非待跑态拒() {
        EvalSetVersionView set = newSet("t278j3s.twice", 0);
        LlmConfigInfo judge = newModel("t278j3m.twice", "internal-judge");
        RunView run = newRun(judge.id(), set.setName(), set.version(), "t278j3-twice");

        judgeDispatchService.dispatch(run.id(), "qwen");
        ValidationException e = assertThrows(ValidationException.class,
                () -> judgeDispatchService.dispatch(run.id(), "qwen"));
        assertTrue(e.getMessage().contains("待跑"), "二发须点名状态：" + e.getMessage());
    }
}
