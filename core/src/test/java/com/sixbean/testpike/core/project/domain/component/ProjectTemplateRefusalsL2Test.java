package com.sixbean.testpike.core.project.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the R63 three-element refusal wording of the industry
 * template doors (create/update name and industry fields, template lookup,
 * content four-piece validation, persistence failures). Every sentence must
 * echo the offending value, quote a literal on-screen entry or payload field
 * in 「」, and carry an action word — pinned without booting infrastructure.
 */
class ProjectTemplateRefusalsL2Test {

    private static void assertThreeElements(String text, String... echoedValues) {
        for (String value : echoedValues) {
            assertTrue(text.contains(value), "offending value not echoed: " + value + " in [" + text + "]");
        }
        assertTrue(text.contains("请"), "missing action word 请: " + text);
        assertTrue(text.matches(".*「[^」]+」.*"), "missing literal repair entry in 「」: " + text);
    }

    @Test
    void echoesSubmittedNameLengthWhenInvalid() {
        assertThreeElements(ProjectTemplateRefusals.nameInvalid("零售订单中台行业模板A".repeat(10)),
                "100", String.valueOf("零售订单中台行业模板A".length() * 10));
    }

    @Test
    void echoesIndustryLengthWhenTooLong() {
        assertThreeElements(ProjectTemplateRefusals.industryTooLong(66), "66", "50");
    }

    @Test
    void echoesTemplateIdWhenMissing() {
        assertThreeElements(ProjectTemplateRefusals.templateMissing(77L), "77");
    }

    @Test
    void namesTheFourPiecesWhenContentMissing() {
        assertThreeElements(ProjectTemplateRefusals.contentMissing(), "SKILL引用", "预置标签", "示例环境", "角色建议");
    }

    @Test
    void pointsAtSkillIdsFieldWhenElementNull() {
        assertThreeElements(ProjectTemplateRefusals.skillIdNull(2), "2", "skillIds");
    }

    @Test
    void echoesDuplicatedSkillId() {
        assertThreeElements(ProjectTemplateRefusals.skillIdDuplicated(15L), "15", "skillIds");
    }

    @Test
    void pointsAtTagListWhenNameBlank() {
        assertThreeElements(ProjectTemplateRefusals.tagNameBlank(1), "1");
    }

    @Test
    void echoesDuplicatedTagName() {
        assertThreeElements(ProjectTemplateRefusals.tagNameDuplicated("回归冒烟"), "回归冒烟");
    }

    @Test
    void pointsAtEnvListWhenNameBlank() {
        assertThreeElements(ProjectTemplateRefusals.envNameBlank(3), "3");
    }

    @Test
    void echoesDuplicatedEnvName() {
        assertThreeElements(ProjectTemplateRefusals.envNameDuplicated("测试环境"), "测试环境");
    }

    @Test
    void echoesTemplateIdWhenStoredContentCorrupted() {
        assertThreeElements(ProjectTemplateRefusals.contentParseFailed(88L), "88");
    }

    @Test
    void namesContentFieldWhenSerializationFails() {
        assertThreeElements(ProjectTemplateRefusals.contentSerializeFailed(), "内容");
    }
}
