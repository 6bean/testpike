package com.sixbean.testpike.core.apiaccess.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessCatalogRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessMcpToolView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadResult;
import com.sixbean.testpike.core.apiaccess.domain.service.ApiAccessService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal doors behind the dialog pass-through capability (02 batch, service-to-service surface).
 *
 * <p>Consumed by the AI service when it assembles and executes the two generic tool entries; the
 * acting operator arrives on the trusted internal header ({@link OperatorContext}) exactly like tool
 * invocation, so the scope guard judges a real identity instead of a model-claimed one.
 */
@RestController
@RequestMapping("/internal/api-access")
public class ApiAccessInternalController {

    private final ApiAccessService apiAccessService;

    public ApiAccessInternalController(ApiAccessService apiAccessService) {
        this.apiAccessService = apiAccessService;
    }

    /**
     * Catalog listing with the reachability verdict of each entry.
     *
     * @param keyword       filter over key, path and parameter names (omit for the whole catalog)
     * @param onlyReachable true keeps only entries the door would actually serve (default true)
     */
    @GetMapping("/endpoints")
    public Response<List<ApiAccessEndpointView>> endpoints(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "true") boolean onlyReachable) {
        return Response.builderSuccess(apiAccessService.catalog(keyword, onlyReachable));
    }

    /**
     * Same listing as the GET door, offered as a POST because the built-in HTTP tool form only
     * dials POST (precedent: the flow-guide read door carries a POST twin for the same reason).
     */
    @PostMapping("/endpoints")
    public Response<List<ApiAccessEndpointView>> endpointsPost(@RequestBody(required = false)
                                                               ApiAccessCatalogRequest request) {
        ApiAccessCatalogRequest safe = request == null ? new ApiAccessCatalogRequest(null, true) : request;
        return Response.builderSuccess(apiAccessService.catalog(safe.keyword(), safe.reachableOnlyOrDefault()));
    }

    /**
     * The external tool face (MCP reads this): catalog entries the door already allows, shaped as
     * tool definitions. GET for a plain poller, POST twin because this project's tool form can only
     * dial POST.
     */
    @GetMapping("/mcp-tools")
    public Response<java.util.List<ApiAccessMcpToolView>> mcpTools() {
        return Response.builderSuccess(apiAccessService.mcpTools());
    }

    /** POST twin of the tool-face listing (same body, no filter arguments). */
    @PostMapping("/mcp-tools")
    public Response<java.util.List<ApiAccessMcpToolView>> mcpToolsPost() {
        return Response.builderSuccess(apiAccessService.mcpTools());
    }

    /**
     * Change door, kept deliberately shut here.
     *
     * <p>It exists so the registry's write tool points at something real instead of a dead url, and
     * it answers with the one true route: a change against this application's own endpoints lands
     * through the draft ledger after a human confirms the card, not from a caller that happens to
     * know the url. Nothing is dispatched from here.
     */
    @PostMapping("/write")
    public Response<ApiAccessWriteResult> write(@RequestBody(required = false) ApiAccessWriteRequest request) {
        String key = request == null ? null : request.endpointKey();
        return Response.builderSuccess(ApiAccessWriteResult.blocked(key,
                "变更需经对话草案卡确认后由落地策略执行，本门不直接代执行"));
    }

    /**
     * Perform one pass-through read for the acting operator.
     *
     * @param taskId budget bucket of the owning dialog task (optional; falls back to the operator)
     */
    @PostMapping("/read")
    public Response<ApiAccessReadResult> read(@RequestParam(required = false) Long taskId,
                                              @RequestBody ApiAccessReadRequest request) {
        return Response.builderSuccess(apiAccessService.read(
                OperatorContext.currentOperatorId(), taskId, request));
    }
}
