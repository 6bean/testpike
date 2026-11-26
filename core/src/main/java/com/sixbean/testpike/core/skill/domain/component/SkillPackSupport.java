package com.sixbean.testpike.core.skill.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.ZipPackSupport;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class SkillPackSupport {

 
    public static final int ZIP_MAX_ENTRIES = ZipPackSupport.ZIP_MAX_ENTRIES;
 
    public static final long ZIP_MAX_UNCOMPRESSED = ZipPackSupport.ZIP_MAX_UNCOMPRESSED;
    
    private static final int FRONTMATTER_MAX_LINES = 2000;

    
    public String extractFirstMarkdown(byte[] zipBytes) {
        for (Map.Entry<String, byte[]> entry : ZipPackSupport.readEntries(zipBytes).entrySet()) {
            if (entry.getKey().toLowerCase().endsWith(".md")) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        throw new ServiceException("zip 包内未找到 .md 主文件"
                + "（SKILL 包需包含一个与 frontmatter name 对应的 Markdown 文件）；请在「SKILL 管理」页修正包内容后重新导入",
                BizErrorCode.VALIDATION_ERROR);
    }

    
    public SkillMarkdownParsed parseMarkdown(String content) {
        if (content == null || content.stripLeading().isEmpty()) {
            throw new ServiceException("SKILL 文件内容为空，请在「SKILL 管理」页编辑该技能补充正文，或重新导入有效文件",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String text = content.replace("\r\n", "\n");
        List<String> lines = text.lines().toList();
        int i = 0;
        while (i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        if (i >= lines.size() || !lines.get(i).trim().equals("---")) {
            throw new ServiceException("SKILL 文件缺少 YAML frontmatter"
                    + "（文件开头需以 --- 包裹元数据块：name/type/stages/scope/version）；请在「SKILL 管理」页按上述结构补齐后重新导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int end = -1;
        for (int j = i + 1; j < lines.size() && j - i <= FRONTMATTER_MAX_LINES; j++) {
            if (lines.get(j).trim().equals("---")) {
                end = j;
                break;
            }
        }
        if (end < 0) {
            throw new ServiceException("SKILL frontmatter 未闭合（缺少结束分隔线 ---），请在「SKILL 管理」页补上结束分隔线后重新导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String yamlBlock = String.join("\n", lines.subList(i + 1, end));
        String body = String.join("\n", lines.subList(end + 1, lines.size())).stripLeading();
        Map<String, Object> meta = parseYaml(yamlBlock);
        validateRequired(meta);
        return new SkillMarkdownParsed(meta, body);
    }

    
    public static byte[] buildZip(String entryName, String text) {
        return ZipPackSupport.buildZip(entryName, text);
    }

    
    private void validateRequired(Map<String, Object> meta) {
        Object name = meta.get("name");
        if (name == null || name.toString().isBlank()) {
            throw new ServiceException("frontmatter 缺少必填字段 name（SKILL 名称），请在「SKILL 管理」页编辑该技能的元数据块补填 name 后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object type = meta.get("type");
        if (type == null) {
            throw new ServiceException("frontmatter 缺少必填字段 type（SKILL 类型，可选："
                    + SkillType.allLabels() + "），请在「SKILL 管理」页补填上述类型之一后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int typeCode;
        try {
            typeCode = Integer.parseInt(type.toString().trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("frontmatter type「" + type + "」不是合法类型（可选："
                    + SkillType.allLabels() + "），请在「SKILL 管理」页改成上述类型之一后重新导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (SkillType.of(typeCode) == null) {
            throw new ServiceException("frontmatter type " + typeCode + " 不在合法枚举（可选："
                    + SkillType.allLabels() + "），请在「SKILL 管理」页改成上述类型之一后重新导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private Map<String, Object> parseYaml(String yamlBlock) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(yamlBlock);
            if (!(loaded instanceof Map)) {
                throw new ServiceException("frontmatter 需为键值结构（name/type/stages/scope/version），请在「SKILL 管理」页按上述键结构改正后重新导入",
                        BizErrorCode.VALIDATION_ERROR);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            return map;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("frontmatter YAML 解析失败：" + rootCauseMessage(e)
                    + "；请在「SKILL 管理」页检查元数据块的缩进与冒号后重新导入",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.lines().findFirst().orElse(msg);
    }

    
    public String reconstructMarkdown(Map<String, Object> frontmatter, String body) {
        StringBuilder sb = new StringBuilder("---\n");
        List<String> ordered = new ArrayList<>();
        for (String key : new String[]{"name", "type", "stages", "scope", "version"}) {
            if (frontmatter.containsKey(key)) {
                ordered.add(key);
            }
        }
        for (String key : frontmatter.keySet()) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }
        for (String key : ordered) {
            sb.append(key).append(": ").append(scalarOrList(frontmatter.get(key))).append('\n');
        }
        sb.append("---\n\n").append(body == null ? "" : body);
        return sb.toString();
    }

    private static String scalarOrList(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(list.get(i));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }
}
