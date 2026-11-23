package com.sixbean.testpike.aiservice.judge.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class HttpEvalTargetAdapter implements EvalTargetAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpEvalTargetAdapter.class);

    
    static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public String type() {
        return "http";
    }

    @Override
    public String fetchEvaluatedText(JudgeRunBrief run, JudgeSampleBrief sample) {
        Map<String, Object> form = parseForm(run);
        String url = requireText(form, "url", run.id());
        String outputJsonPath = requireText(form, "outputJsonPath", run.id());
        String method = form.get("method") == null ? "POST"
                : String.valueOf(form.get("method")).toUpperCase();
        long timeoutMs = form.get("timeoutMs") instanceof Number n
                ? n.longValue() : DEFAULT_TIMEOUT_MS;

        String payload = buildPayload(form, sample, run.id());
        assertNoCredLeftover(payload, form.get("headers"), run.id());

        String responseBody = call(url, method, payload, form.get("headers"), timeoutMs, run.id());
        String extracted;
        try {
            extracted = GenKit.jsonPathExtract(responseBody, outputJsonPath);
        } catch (ServiceException e) {
            throw new ServiceException("被测系统响应里取不到待评文本（JSONPath「" + outputJsonPath
                    + "」）：" + e.getMessage() + "——runId=" + run.id()
                    + "，请核对映射表单的输出路径与响应结构");
        }
        if (extracted == null || extracted.isBlank()) {
            throw new ServiceException("被测系统返回的待评文本为空（JSONPath「" + outputJsonPath
                    + "」提取结果为空白）——runId=" + run.id() + "，拒绝对空产出评分");
        }
        log.info("外部被测系统投喂完成 runId={} url={} 响应长度={} 待评长度={}",
                run.id(), url, responseBody.length(), extracted.length());
        return extracted;
    }

    private Map<String, Object> parseForm(JudgeRunBrief run) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> form = objectMapper.readValue(run.targetRef(), Map.class);
            return form;
        } catch (Exception e) {
            throw new ServiceException("被评目标配置 target_ref 不是合法 JSON 对象（runId="
                    + run.id() + "）——表单式映射要求 {\"url\":...,\"outputJsonPath\":...} 形态");
        }
    }

    private String requireText(Map<String, Object> form, String field, long runId) {
        Object value = form.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ServiceException("被评目标映射缺少必填项「" + field + "」（runId=" + runId
                    + "）——请在评测运行的目标表单里补全");
        }
        return String.valueOf(value);
    }

    
    private String buildPayload(Map<String, Object> form, JudgeSampleBrief sample, long runId) {
        String input = sample.input() == null ? "" : sample.input();
        Object inputJsonPath = form.get("inputJsonPath");
        if (inputJsonPath != null && !String.valueOf(inputJsonPath).isBlank()) {
            try {
                input = GenKit.jsonPathExtract(input, String.valueOf(inputJsonPath));
            } catch (ServiceException e) {
                throw new ServiceException("样本 input 里取不到投喂值（JSONPath「" + inputJsonPath
                        + "」）：" + e.getMessage() + "——runId=" + runId
                        + "，请核对映射表单的输入路径与样本结构");
            }
        }
        Object template = form.get("bodyTemplate");
        if (template == null || String.valueOf(template).isBlank()) {
            return input;
        }
        return String.valueOf(template).replace("{{input}}", input);
    }

    
    private void assertNoCredLeftover(String payload, Object headers, long runId) {
        String flat = payload + (headers == null ? "" : headers.toString());
        if (flat.contains("{{CRED:")) {
            throw new ServiceException("被评目标映射里残留凭据引用 {{CRED:...}} 未替换（runId="
                    + runId + "）——凭据实值应由平台在取用前注入，请确认引用键已在凭据保险箱建档启用");
        }
    }

    private String call(String url, String method, String body, Object headers,
                        long timeoutMs, long runId) {
        HttpResponse<String> response;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs));
            if (headers instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    builder.header(String.valueOf(entry.getKey()),
                            String.valueOf(entry.getValue()));
                }
            }
            if ("GET".equals(method) || "HEAD".equals(method)) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method,
                        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (!hasHeaderNamed(headers, "Content-Type")) {
                    builder.header("Content-Type", "application/json");
                }
            }
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("调用被测 AI 系统被中断——runId=" + runId + " url=" + url);
        } catch (Exception e) {
            throw new ServiceException("被测 AI 系统调用失败（端点不可达或超时）——runId=" + runId
                    + " url=" + url + " 原因=" + e.getClass().getSimpleName()
                    + "；拒绝对取不到产出的目标评分");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException("被测 AI 系统返回异常状态 " + response.statusCode()
                    + "——runId=" + runId + " url=" + url + "，拒绝对失败响应评分");
        }
        return response.body();
    }

    private boolean hasHeaderNamed(Object headers, String name) {
        if (!(headers instanceof Map<?, ?> map)) {
            return false;
        }
        return map.keySet().stream().anyMatch(k -> name.equalsIgnoreCase(String.valueOf(k)));
    }
}
