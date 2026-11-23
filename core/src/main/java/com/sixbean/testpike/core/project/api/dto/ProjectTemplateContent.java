package com.sixbean.testpike.core.project.api.dto;

import java.util.List;


public record ProjectTemplateContent(List<Long> skillIds, List<TemplateTagItem> tags,
                                     List<TemplateEnvItem> envs, List<String> roleHints) {

    
    public List<Long> skillIdsOrEmpty() {
        return skillIds == null ? List.of() : skillIds;
    }

    public List<TemplateTagItem> tagsOrEmpty() {
        return tags == null ? List.of() : tags;
    }

    public List<TemplateEnvItem> envsOrEmpty() {
        return envs == null ? List.of() : envs;
    }

    public List<String> roleHintsOrEmpty() {
        return roleHints == null ? List.of() : roleHints;
    }
}
