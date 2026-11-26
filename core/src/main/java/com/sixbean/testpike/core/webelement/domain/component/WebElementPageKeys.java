package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.springframework.stereotype.Component;

/**
 * Fold a page URL into the ledger page key: lowercased origin + the shared
 * template-folded path (WE batch W4). The segment folding is consumed from
 * ContractMatchKeyRules — the same single source that lands the contract
 * endpoint keys on one row — so /pets/123, /pets/{id} and case-variant hosts
 * never fragment one element asset into several rows.
 */
@Component
public class WebElementPageKeys {

    private final ContractMatchKeyRules rules;

    public WebElementPageKeys(ContractMatchKeyRules rules) {
        this.rules = rules;
    }

    /**
     * Canonical page key from any absolute http(s) URL; query and fragment are
     * dropped. Parsing is kept deliberately light (plain splits) because
     * template-style URLs legitimately carry {var} braces that java.net.URI
     * rejects, and those must fold onto the same row as concrete URLs.
     */
    public String pageKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ServiceException("页面地址不能为空，请填写页面的完整 http(s) 地址，"
                    + "格式：http://主机[:端口]/路径",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = rawUrl.trim();
        int sep = trimmed.indexOf("://");
        String scheme = sep < 0 ? "" : trimmed.substring(0, sep).toLowerCase();
        if ((!("http".equals(scheme) || "https".equals(scheme)))) {
            throw new ServiceException("页面地址必须以 http:// 或 https:// 开头并包含主机名（当前 %s），"
                    + "请改成完整地址，示例：https://example.com/order/list",
                    BizErrorCode.VALIDATION_ERROR, rawUrl);
        }
        String rest = trimmed.substring(sep + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.isBlank()) {
            throw new ServiceException("页面地址缺少主机名（当前 %s），请填写完整 URL，"
                    + "格式：http://主机[:端口]/路径",
                    BizErrorCode.VALIDATION_ERROR, rawUrl);
        }
        String host = authority;
        int port = -1;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new ServiceException("页面地址端口无法识别（当前 %s），端口必须是数字，"
                        + "请改成格式：http://主机:8080/路径",
                        BizErrorCode.VALIDATION_ERROR, rawUrl);
            }
        }
        if (host.isBlank()) {
            throw new ServiceException("页面地址缺少主机名（当前 %s），请填写完整 URL，"
                    + "格式：http://主机[:端口]/路径",
                    BizErrorCode.VALIDATION_ERROR, rawUrl);
        }
        StringBuilder origin = new StringBuilder(scheme).append("://").append(host.toLowerCase());
        if (port > 0 && !isDefaultPort(scheme, port)) {
            origin.append(':').append(port);
        }
        return origin.append(rules.foldedPath(path.isEmpty() ? "/" : path)).toString();
    }

    private boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }
}
