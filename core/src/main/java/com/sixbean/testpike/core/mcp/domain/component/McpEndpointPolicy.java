package com.sixbean.testpike.core.mcp.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;


@Component
public class McpEndpointPolicy {

    
    private static final String[] METADATA_HOSTS = {"169.254.169.254", "fd00:ec2::254"};

    private final ProdBlacklistGuard prodBlacklistGuard;

    public McpEndpointPolicy(ProdBlacklistGuard prodBlacklistGuard) {
        this.prodBlacklistGuard = prodBlacklistGuard;
    }

    
    public String validate(String raw, String fieldLabel) {
        return validate(raw, fieldLabel, "MCP ");
    }

    
    public String validate(String raw, String fieldLabel, String sourcePrefix) {
        String value = raw == null ? "" : raw.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw bad(sourcePrefix, fieldLabel, value, "不是合法 URL（须 http/https 完整地址）");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw bad(sourcePrefix, fieldLabel, value, "须为 http/https 完整 URL（含主机名）");
        }
        if (prodBlacklistGuard.matches(value)) {
            throw bad(sourcePrefix, fieldLabel, value,
                    "命中生产环境域名黑名单（TESTPIKE_PROD_BLACKLIST），拒绝注册");
        }
        String host = uri.getHost().toLowerCase();
        for (String metadata : METADATA_HOSTS) {
            if (host.equals(metadata)) {
                throw bad(sourcePrefix, fieldLabel, value, "指向云元数据端点（SSRF 防护），拒绝注册");
            }
        }
        return value;
    }

    private static ServiceException bad(String sourcePrefix, String fieldLabel, String value, String why) {
        return new ServiceException(sourcePrefix + fieldLabel + "「" + value + "」" + why,
                BizErrorCode.VALIDATION_ERROR);
    }
}
