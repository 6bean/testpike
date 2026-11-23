package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;

import java.util.List;


public interface ApiSpecService {

    /**
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException
 */
    ApiSpecInfo upload(long operatorId, long projectId, String name,
                       byte[] content, Long documentId);

    /**
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException
 */
    ApiSpecInfo fetchFromUrl(long operatorId, long projectId, String name, String url);

    /**
 * Pull the LATEST version of a provider project's spec into the consumer project's
 * version chain . Reads the provider's
 * newest spec_text and feeds it through the same upload core as fetchFromUrl — the
 * parse/SHA256 idempotence/version-chain diff semantics stay single source.
 *
 * @throws com.sixbean.testpike.common.exception.ServiceException
 * provider holds no spec with that name = 404 human-readable;
 * identical content = 409 (unchanged signal for the scanner)
 */
    ApiSpecInfo fetchFromProviderSpec(long operatorId, long projectId, String name,
                                      long providerProjectId, String providerSpecName);

    
    ApiSpecInfo get(long id);

    
    List<ApiSpecInfo> history(long projectId, String name);

    
    PageResult<ApiSpecInfo> list(long projectId, int page, int size);
}
