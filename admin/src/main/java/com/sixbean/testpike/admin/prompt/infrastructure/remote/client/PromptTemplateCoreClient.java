package com.sixbean.testpike.admin.prompt.infrastructure.remote.client;

import com.sixbean.testpike.admin.prompt.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateFamilyView;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateSaveRequest;
import com.sixbean.testpike.admin.prompt.api.dto.PromptTemplateView;

import java.util.List;


public interface PromptTemplateCoreClient {

    
    PromptTemplateView save(PromptTemplateSaveRequest request);

    
    PromptTemplateView rollback(long sourceId, long operatorId);

    
    PromptTemplateView detail(long id);

    
    List<PromptTemplateView> listVersions(String name, String domain);

    /**
     * Family enumeration through each family's newest version (optional domain
     * filter; null = every family). Read-side mirror of core
     * GET /internal/prompt-templates/families — no rule is judged here.
     */
    List<PromptTemplateFamilyView> listFamilies(String domain);

    
    PromptTemplateView latest(String name, String domain);

    
    PromptTemplateView latestByStatus(String name, String domain, int status);

    
    PromptTemplateView promote(long id);

    /**
 * Gray-rollout advisory of one template family —
 * relay of core GET /internal/behavior/rollout-advisory. Advisory-grade:
 * core computes the result-rate comparison and the rollback suggestion,
 * the bridge judges nothing. NULL means the family holds no live gray row.
 */
    BehaviorRolloutView rolloutAdvisory(String domain, String name);
}
