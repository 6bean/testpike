package com.sixbean.testpike.core.skill.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill family bridge from a governed read row to the projects that govern it.
 *
 * <p>The skill detail path already answers "which project does this skill belong to", so the
 * bridge delegates to it instead of opening a second ownership query. The skill market has two
 * visibility tiers on its page — project skills are member-gated, system-level skills (empty
 * project column) are readable by every logged-in user — and the bridge mirrors exactly that:
 * a system-level row answers with an empty project set so the door asks no membership question,
 * which keeps the pass-through door from becoming stricter than R34's system-bucket default
 * visibility.
 */
@Component
public class SkillRowProjectLookup implements RowProjectLookup {

    private final SkillService skillService;

    public SkillRowProjectLookup(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String entity() {
        return "skill";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long skillId = RowIdValues.longOrNull(rowIdValue);
        if (skillId == null) {
            return null;
        }
        SkillInfo skill = RowOwnershipReads.rowOrAbsent(() -> skillService.get(skillId));
        if (skill == null) {
            return null;
        }
        return skill.projectId() == null ? List.of() : List.of(skill.projectId());
    }
}
