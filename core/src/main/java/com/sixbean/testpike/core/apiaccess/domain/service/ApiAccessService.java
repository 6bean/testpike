package com.sixbean.testpike.core.apiaccess.domain.service;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadResult;

import java.util.List;

/**
 * Pass-through door over this application's own endpoints.
 *
 * <p>Two abilities: list what the catalog holds together with the reachability verdict, and perform
 * one read the policy, the scope guard and the budget all cleared. Writes are out of scope for this
 * door by design — a change proposal has to land as a confirmable draft, not as a side effect of a
 * model call.
 */
public interface ApiAccessService {

    /**
     * Catalog rows the dialog may consider, filtered and ordered for a reader.
     *
     * @param keyword      case-insensitive filter over key/path/parameter names (null = all)
     * @param onlyReachable true to drop rows the door will refuse anyway
     */
    List<ApiAccessEndpointView> catalog(String keyword, boolean onlyReachable);

    /**
     * The external tool face: read entries the door already allows, projected as MCP tools.
     *
     * <p>Generated from the same catalog the dialog reads, so an endpoint that appears in the
     * product appears outside it in the same batch, with no list for anyone to forget.
     */
    java.util.List<com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessMcpToolView> mcpTools();

    /**
     * Perform one pass-through read.
     *
     * @param operatorId acting user, stamped by the trusted chain (null refuses: no identity, no read)
     * @param taskId     dialog/agent task the call belongs to, used as the budget bucket (nullable)
     * @param request    which catalog entry with which declared arguments
     */
    ApiAccessReadResult read(Long operatorId, Long taskId, ApiAccessReadRequest request);

    /**
     * Land one confirmed change proposal.
     *
     * <p>Only the draft confirmation door calls this: the human already saw the card and clicked
     * accept, the acting identity is the one who confirmed, and the owning project is the chat
     * session's project — never a value the proposal carries. Everything the read path checks still
     * applies (policy, exclusion families, membership, budget), plus the change switch and the
     * declared-body whitelist.
     *
     * @param operatorId   the human who confirmed
     * @param sessionProjectId owning project taken from the chat session
     * @param request      which catalog entry with which declared payload
     */
    ApiAccessWriteResult landConfirmedChange(Long operatorId, long sessionProjectId,
                                             ApiAccessWriteRequest request);
}
