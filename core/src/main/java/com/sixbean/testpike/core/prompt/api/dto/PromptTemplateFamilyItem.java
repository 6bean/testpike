package com.sixbean.testpike.core.prompt.api.dto;

/**
 * One template family as seen through its latest version (the enumeration answer to "which
 * prompt-template domains and names exist right now"). Families are (domain, name) pairs whose
 * versions are independent; this row carries the newest version of the family plus the size of
 * the family, so a reader learns what exists before asking for a family's full version list or
 * for one row's content.
 *
 * @param domain        business domain of the family
 * @param name          template name of the family
 * @param latestId      row id of the newest version (tasks pin this id)
 * @param latestVersion version number of the newest version
 * @param latestStatus  status of the newest version (1 gray, 2 full, 3 history)
 * @param versionCount  how many versions the family holds
 * @param updatedAt     creation time of the newest version ('yyyy-MM-dd HH:mm:ss')
 */
public record PromptTemplateFamilyItem(String domain, String name, Long latestId,
                                       Integer latestVersion, Integer latestStatus,
                                       Integer versionCount, String updatedAt) {
}
