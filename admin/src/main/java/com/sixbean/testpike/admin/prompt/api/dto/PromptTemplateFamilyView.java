package com.sixbean.testpike.admin.prompt.api.dto;

/**
 * One prompt-template family seen through its newest version — mirror of core
 * PromptTemplateFamilyItem (seven fields kept whole; no cross-module reference of
 * core types, the PromptTemplateView double-mirror precedent applies). The template
 * management page reads this list as its first screen ("which families exist"), then
 * opens a family to fetch that family's full version list.
 *
 * @param domain        business domain of the family
 * @param name          template name of the family
 * @param latestId      row id of the newest version (tasks pin this id)
 * @param latestVersion version number of the newest version
 * @param latestStatus  status of the newest version (1 gray, 2 full, 3 history)
 * @param versionCount  how many versions the family holds
 * @param updatedAt     creation time of the newest version ('yyyy-MM-dd HH:mm:ss')
 */
public record PromptTemplateFamilyView(String domain, String name, Long latestId,
                                       Integer latestVersion, Integer latestStatus,
                                       Integer versionCount, String updatedAt) {
}
