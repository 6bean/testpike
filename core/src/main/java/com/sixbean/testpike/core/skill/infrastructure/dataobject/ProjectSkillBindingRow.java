package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillItem;
import com.sixbean.testpike.core.skill.domain.component.SkillType;

import java.sql.Timestamp;


public class ProjectSkillBindingRow {

    private Long skillId;
    private Integer role;
    private Integer priority;
    private Integer enabled;
    private Timestamp boundAt;
    private String name;
    private Integer type;

 
    public ProjectSkillItem toItem() {
        int typeCode = type == null ? 0 : type;
        return new ProjectSkillItem(skillId, role, priority, enabled,
                ViewFormats.formatTs(boundAt), name, SkillType.labelOf(typeCode));
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
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

    public Timestamp getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Timestamp boundAt) {
        this.boundAt = boundAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }
}
