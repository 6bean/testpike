package com.sixbean.testpike.core.skill.domain.component;

import java.util.Map;


public record SkillMarkdownParsed(Map<String, Object> frontmatter, String body) {
}
