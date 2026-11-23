package com.sixbean.testpike.core.prompt.domain.service;

import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateFamilyItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;

import java.util.List;


public interface PromptTemplateService {

    
    PromptTemplateItem save(PromptTemplateSaveInput input);

    
    PromptTemplateItem rollback(Long sourceId, Long operatorId);

    
    PromptTemplateItem detail(Long id);

    
    List<PromptTemplateItem> listVersions(String name, String domain);

    /**
     * Family enumeration: one row per (domain, name) pair carrying its newest version and family
     * size — the answer to "which prompt templates exist" before reading any family in detail.
     *
     * @param domain optional filter; blank or null lists every domain
     */
    List<PromptTemplateFamilyItem> listFamilies(String domain);

    
    PromptTemplateItem latest(String name, String domain);

    
    PromptTemplateItem latestByStatus(String name, String domain, int status);

    
    PromptTemplateItem latestGrayBefore(String name, String domain, int upperExclusive);

    
    PromptTemplateItem promote(Long id);
}
