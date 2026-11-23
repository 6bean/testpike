package com.sixbean.testpike.core.skill.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipDoor;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Skill family bridge on the read door: governed rows say "skill"/"id" and this bridge turns the
 * row id into owning projects through the one existing skill detail path. The skill market keeps
 * the page's two visibility tiers: a project skill is gated by its project's membership, while a
 * system-level skill (no project owns it) stays readable by every logged-in operator — the same
 * verdict the page applies when its project column is empty. Properties held here: the entity name
 * routes, numeric and string id forms resolve, the system bucket answers with an empty project set
 * that the door serves without a membership question, a missing row fails closed, unrelated service
 * problems are not swallowed and a non-numeric argument never reaches the service.
 */
class SkillRowProjectLookupTest {

    private static final long SKILL_ID = 611L;
    private static final long OWNING_PROJECT = 7L;
    private static final long OPERATOR = 42L;

    private SkillService skillService;
    private SkillRowProjectLookup lookup;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        lookup = new SkillRowProjectLookup(skillService);
    }

    private static SkillInfo skillRow(Long projectId) {
        return new SkillInfo(SKILL_ID, projectId, "casegen-guide", 1, "prompt",
                2, "published", 3, 3, "preview body", null,
                null, null, null, null, null, null);
    }

    private ApiAccessGovernanceView anchoredSkillRow() {
        return new ApiAccessGovernanceView("GET /internal/skills/{id}", true, true,
                null, null, "skill", "id");
    }

    @Test
    void entityNameRoutesTheDoorToThisBridge() {
        when(skillService.get(SKILL_ID)).thenReturn(skillRow(OWNING_PROJECT));
        RowOwnershipDoor.assertEntityNameRoutes(lookup, "skill", anchoredSkillRow(),
                "id", SKILL_ID, OWNING_PROJECT, OPERATOR);
    }

    @Test
    void projectSkillResolvesToItsOwningProject() {
        when(skillService.get(SKILL_ID)).thenReturn(skillRow(OWNING_PROJECT));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds(SKILL_ID));
        assertEquals(List.of(OWNING_PROJECT), lookup.ownerProjectIds("611"),
                "path arguments arrive as strings and must resolve like numbers");
    }

    @Test
    void systemLevelSkillResolvesToAnEmptyProjectSet() {
        when(skillService.get(SKILL_ID)).thenReturn(skillRow(null));
        assertEquals(List.of(), lookup.ownerProjectIds(SKILL_ID),
                "a system-level skill exists but no project owns it — empty set, not a miss");
    }

    @Test
    void doorServesASystemLevelSkillWithoutAskingMembership() {
        when(skillService.get(SKILL_ID)).thenReturn(skillRow(null));
        assertNull(RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredSkillRow(), OPERATOR, Map.of("id", SKILL_ID)),
                "the page lets every logged-in user read system-level skills; the door must match");
    }

    @Test
    void doorRefusesAnOutsiderOnAProjectSkill() {
        when(skillService.get(SKILL_ID)).thenReturn(skillRow(OWNING_PROJECT));
        String problem = RowOwnershipDoor.door(OPERATOR, false, lookup)
                .problem(anchoredSkillRow(), OPERATOR, Map.of("id", SKILL_ID));
        assertNotNull(problem);
        assertTrue(problem.contains("越权读取已被拒绝"), problem);
    }

    @Test
    void missingSkillResolvesToNothing() {
        when(skillService.get(999L)).thenThrow(
                new ServiceException("skill not found", BizErrorCode.NOT_FOUND));
        RowOwnershipDoor.assertMissingRowIsRefused(lookup, anchoredSkillRow(),
                "id", 999L, OPERATOR);
    }

    @Test
    void unrelatedServiceProblemsAreNotSwallowed() {
        when(skillService.get(SKILL_ID)).thenThrow(new ServiceException("db down", 500));
        assertThrows(ServiceException.class, () -> lookup.ownerProjectIds(SKILL_ID),
                "only the not-found verdict may be folded into an unresolvable row");
    }

    @Test
    void nonNumericArgumentIsNotResolvableAndNeverReachesTheService() {
        RowOwnershipDoor.assertNonNumericArgumentIsInert(lookup, "abc", null);
        verifyNoInteractions(skillService);
    }
}
