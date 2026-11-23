package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;


public class SkillAssemblyRow {

    private Long skillId;
    private String name;
    private Integer role;
    private Integer priority;
    private Integer enabled;
    private Integer version;
    private String body;
    private Integer kind;

    
    public SkillAssemblyItem toItem() {
        return new SkillAssemblyItem(skillId, name, role, priority, enabled, version, body,
                kind == null ? 0 : kind);
    }

    public Integer getKind() {
        return kind;
    }

    public void setKind(Integer kind) {
        this.kind = kind;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
