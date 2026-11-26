package com.sixbean.testpike.core.plugin.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class PluginDescriptorSupport {

    
    public static final int MAX_BYTES = 64 * 1024;

    
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "name", "industry", "version", "content_pack", "default_skills", "param_template", "project_types");

    
    public static final List<String> REQUIRED_KEYS = List.of("name", "industry", "version");

    
    public static final Set<String> EXECUTABLE_FORBIDDEN_KEYS = Set.of(
            "executable", "script", "command", "code", "hook", "hooks",
            "shell", "python", "jar", "class", "main", "entrypoint");

    private static final String LIST_JOIN = String.join(" / ", List.of(ALLOWED_KEYS.toArray(String[]::new)));

    
    public Map<String, Object> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ServiceException("plugin.yaml 内容为空（需为声明式描述符：name/industry/version 必填），请补全内容后重新上传（示例：name: 冒烟巡检）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ServiceException("plugin.yaml 超过 64KB 上限（描述符仅承载声明式元数据，"
                    + "正文资产请走内容包/SKILL 通道），请删减正文类长文本后重新上传「plugin.yaml」", BizErrorCode.VALIDATION_ERROR);
        }
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            loaded = new Yaml(new SafeConstructor(options)).load(content);
        } catch (Exception ex) {
            throw new ServiceException("plugin.yaml 不是合法 YAML 映射（解析失败："
                    + firstLine(ex.getMessage()) + "），请对照「键: 值」逐行修正缩进与冒号后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new ServiceException("plugin.yaml 不是合法 YAML 映射（顶层需为键值映射，"
                    + "实际为" + (loaded == null ? "空文档" : "标量/序列") + "），请将顶层改成键值映射（示例：name: 冒烟巡检）后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
        rejectExecutable(raw, "");
        Map<String, Object> manifest = normalize(raw);
        for (String key : REQUIRED_KEYS) {
            Object v = manifest.get(key);
            if (v instanceof Number || v instanceof Boolean) {
                v = String.valueOf(v);
                manifest.put(key, v);
            }
            if (!(v instanceof String s) || s.isBlank()) {
                throw new ServiceException("plugin.yaml 缺少必填键：" + key
                        + "（必填：" + String.join("/", REQUIRED_KEYS) + "；合法键全集：" + LIST_JOIN + "），请补填缺失键（示例：name: 冒烟巡检）后重新上传",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        for (String key : manifest.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new ServiceException("plugin.yaml 含未知键：" + key
                        + "（合法键：" + LIST_JOIN + "；插件为声明式描述符，禁夹带其他字段），请按合法键改成正确写法后重新上传",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        requireType(manifest, "content_pack", String.class);
        requireType(manifest, "default_skills", List.class);
        requireType(manifest, "param_template", Map.class);
        requireType(manifest, "project_types", List.class);
        return manifest;
    }

    
    private void rejectExecutable(Map<?, ?> node, String path) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (EXECUTABLE_FORBIDDEN_KEYS.contains(key.toLowerCase())) {
                throw new ServiceException("插件描述符禁可执行字段：" + childPath
                        + "（H8 安全底线=声明式描述符；可执行能力唯一路径=U 域工具契约+审批），可执行能力请通过「工具管理 → 工具市场 → 导入工具」提交契约并走审批",
                        BizErrorCode.VALIDATION_ERROR);
            }
            walkForExecutable(entry.getValue(), childPath);
        }
    }

    private void walkForExecutable(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            rejectExecutable(map, path);
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                walkForExecutable(item, path);
            }
        }
    }

    
    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            out.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return out;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalize(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalizeValue(item));
            }
            return out;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    
    private void requireType(Map<String, Object> manifest, String key, Class<?> expected) {
        Object v = manifest.get(key);
        if (v != null && !expected.isInstance(v)) {
            throw new ServiceException("plugin.yaml 键 " + key + " 类型不合法（需为"
                    + typeName(expected) + "），请改成正确写法（示例：列表键写成 - 开头的行）后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String typeName(Class<?> expected) {
        return List.class.isAssignableFrom(expected) ? "字符串列表"
                : Map.class.isAssignableFrom(expected) ? "键值映射" : "字符串";
    }

    
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "未知解析错误";
        }
        Set<String> one = new LinkedHashSet<>();
        one.add(message.lines().findFirst().orElse("未知解析错误").strip());
        return one.iterator().next();
    }
}
