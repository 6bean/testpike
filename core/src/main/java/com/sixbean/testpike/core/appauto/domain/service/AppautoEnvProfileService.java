package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;

import java.util.List;


public interface AppautoEnvProfileService {

    
    long create(long projectId, AppautoEnvProfileInput input);

    
    void update(long id, AppautoEnvProfileInput input);

    
    AppautoEnvProfileInfo get(long id);

    
    List<AppautoEnvProfileInfo> list(long projectId, String profileKind);

    
    void delete(long id);

    /**
 * Resolve the CURRENT head version of a bound login recipe and return its app.*
 * step sequence — the single source consumed by both the save-side binding check
 * and the dispatch envelope assembly (W5 phase 2, segment B1b). The returned steps
 * still carry credential placeholders; resolution stays at the dispatch
 * injection single point (this method never touches the vault).
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException the reference is
 * missing, points to another kind, its chain is withdrawn, or its payload
 * is not an app-variant recipe — always fail-closed with an honest message
 */
    java.util.List<java.util.Map<String, Object>> resolveLoginRecipeSteps(long recipeAssetId);
}
