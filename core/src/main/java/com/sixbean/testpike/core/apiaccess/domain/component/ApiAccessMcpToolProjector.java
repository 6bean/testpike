package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessMcpToolView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Projects the pass-through catalog into MCP tool definitions for callers outside the product.
 *
 * <p>Produced here rather than in the MCP face on purpose: this is the only component that knows what
 * an endpoint declares, and an external surface that kept its own list would drift the moment an
 * endpoint changes. New or changed endpoints therefore appear without anybody re-listing anything.
 *
 * <p>Read entries only. A change needs a human looking at a card before it lands, and an inbound MCP
 * call has no such channel — so the change face is deliberately absent here instead of being
 * reachable with a weaker confirmation. The reachability test is the door's own verdict, so family
 * exclusions, unanchored endpoints and the governance overlay apply to the external face exactly as
 * they do inside the dialog: an agent sees fewer tools than a curious developer could wish for, and
 * every one of them is one the platform already decided it may serve.
 */
@Component
public class ApiAccessMcpToolProjector {

    /** Tool-name budget of the MCP wire; longer paths are folded with a stable suffix, never cut. */
    private static final int NAME_LIMIT = 58;

    /**
     * Exposure switch for this face (K8 hot-updatable), shipped CLOSED.
     *
     * <p>Deliberate difference from the dialog's read door, which ships open: that door serves a
     * signed-in member of this installation, while this face serves whatever holds an API token with
     * scope mcp.read from outside it — endpoint names and parameter surfaces then leave the product.
     * An outward-facing data surface is opened by an operator on the configuration page, not by
     * default; the same named exemption class as the change landing, recorded in
     * db/migration/V367__mcp_api_access_exposure.sql.
     */
    public static final String KEY_EXPOSED = "mcp.api-access.enabled";

    private final ApiEndpointCatalog catalog;
    private final ApiAccessPolicy policy;
    private final ApiAccessGovernanceRepository governance;
    private final SystemConfigReader configReader;

    public ApiAccessMcpToolProjector(ApiEndpointCatalog catalog, ApiAccessPolicy policy,
                                     ApiAccessGovernanceRepository governance,
                                     com.sixbean.testpike.core.config.domain.component
                                             .SystemConfigReader configReader) {
        this.catalog = catalog;
        this.policy = policy;
        this.governance = governance;
        this.configReader = configReader;
    }

    /** The external tool face, stably ordered (an agent comparing two lists must see no shuffle). */
    public List<ApiAccessMcpToolView> tools() {
        if (!configReader.getBoolean(KEY_EXPOSED, false)) {
            return List.of();
        }
        Map<String, ApiAccessGovernanceView> overlay = governance.loadAll();
        List<ApiAccessMcpToolView> out = new ArrayList<>();
        for (ApiEndpointSpec spec : catalog.endpoints().values()) {
            if (spec.writeClass()) {
                continue;
            }
            // A permission-gated page face cannot be promised to the external tool surface:
            // inbound MCP calls carry no natural-person operator for the gate to honour, so the
            // door would honestly refuse every call the projection just advertised. Skip them.
            ApiAccessGovernanceView gateRow = overlay.get(spec.key());
            if (gateRow != null && gateRow.requiredPermission() != null
                    && !gateRow.requiredPermission().isBlank()) {
                continue;
            }
            if (!policy.decide(spec.withoutBody(), overlay, false).allowed()) {
                continue;
            }
            out.add(new ApiAccessMcpToolView(toolName(spec), describe(spec), schema(spec), spec.key()));
        }
        out.sort(Comparator.comparing(ApiAccessMcpToolView::name));
        return out;
    }

    /**
     * Deterministic name from method + path: `api_get_internal_tags`. Slugs are stable across
     * restarts, so a caller's saved integration keeps pointing at the same endpoint.
     */
    static String toolName(ApiEndpointSpec spec) {
        String raw = ("api_" + spec.httpMethod() + "_" + spec.pathPattern())
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (raw.length() <= NAME_LIMIT) {
            return raw;
        }
        return raw.substring(0, NAME_LIMIT) + "_" + Integer.toHexString(spec.key().hashCode());
    }

    private String describe(ApiEndpointSpec spec) {
        List<String> supplied = new ArrayList<>();
        spec.params().stream().filter(ApiEndpointParam::addressable).forEach(p -> supplied.add(p.name()));
        String arguments = supplied.isEmpty() ? "无参数" : String.join("、", supplied);
        String anchor = spec.projectAnchor() == null
                ? "该条目不带项目归属，返回范围由平台判定"
                : "归属项目按参数 " + spec.projectAnchor() + " 判定，调用者须是该项目成员";
        return "直读本系统接口 " + spec.key() + "；可填参数：" + arguments + "。" + anchor
                + "；结果受调用预算与返回宽度限制，超限会说明被截断。";
    }

    /** JSON Schema over exactly the addressable parameters the entry declares. */
    private String schema(ApiEndpointSpec spec) {
        StringBuilder properties = new StringBuilder();
        List<String> required = new ArrayList<>();
        for (ApiEndpointParam param : spec.params()) {
            if (!param.addressable()) {
                continue;
            }
            if (properties.length() > 0) {
                properties.append(',');
            }
            properties.append(jsonQuote(param.name())).append(":{\"type\":\"").append(jsonType(param))
                    .append("\",\"description\":\"参数 ").append(escape(param.name()))
                    .append("（").append(param.in()).append("）\"");
            if (param.defaultValue() != null) {
                properties.append(",\"default\":").append(literal(param));
            }
            properties.append('}');
            if (param.required()) {
                required.add(param.name());
            }
        }
        StringBuilder schema = new StringBuilder("{\"type\":\"object\",\"properties\":{")
                .append(properties).append("}");
        if (!required.isEmpty()) {
            schema.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) {
                    schema.append(',');
                }
                schema.append(jsonQuote(required.get(i)));
            }
            schema.append(']');
        }
        return schema.append(",\"additionalProperties\":false}").toString();
    }

    /** Declared Java type -> JSON Schema type, with arrays of scalars kept as arrays. */
    private String jsonType(ApiEndpointParam param) {
        String type = param.type() == null ? "" : param.type().toLowerCase();
        if (type.contains("int") || type.contains("long") || type.contains("short")) {
            return "integer";
        }
        if (type.contains("double") || type.contains("float") || type.contains("decimal")) {
            return "number";
        }
        if (type.contains("bool")) {
            return "boolean";
        }
        if (type.contains("list") || type.contains("array") || type.endsWith("[]")) {
            return "array";
        }
        return "string";
    }

    private String literal(ApiEndpointParam param) {
        return "string".equals(jsonType(param)) ? jsonQuote(param.defaultValue()) : param.defaultValue();
    }

    private String jsonQuote(String value) {
        return "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
