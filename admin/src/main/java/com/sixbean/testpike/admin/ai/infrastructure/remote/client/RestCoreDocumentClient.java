package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.ChunkContextView;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionRequest;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionResultView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentCategoryRequest;
import com.sixbean.testpike.admin.ai.api.dto.DocumentChunkView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentUsageSwitchesRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionPreviewView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionEventView;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;


@Component
public class RestCoreDocumentClient implements CoreDocumentClient {

    private final RestClient rest;

    /**
     * Dedicated forwarding leg for the full-store confirm door: landing hundreds of
     * preview items inside one core transaction legitimately runs longer than the 30s
     * default internal read window (golden-window red B: 874 items fell into the generic
     * downstream-unreachable 500 while core was still working). This route carries its
     * own budget, factory default 360s mirroring the scenario-preview forward precedent
     * (ai.scenario-preview.forward-read-timeout-seconds). Adjust the admin startup
     * property ai.document.confirm-all-forward-read-timeout-seconds; takes effect on restart.
     */
    private final RestClient confirmAllRest;
    private final Duration confirmAllWindow;

    public RestCoreDocumentClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl,
                                  @Value("${ai.document.confirm-all-forward-read-timeout-seconds:820}")
                                          long confirmAllForwardSeconds) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
        this.confirmAllWindow = Duration.ofSeconds(confirmAllForwardSeconds);
        this.confirmAllRest = RestClientInvoker.defaultRestClient(coreBaseUrl, this.confirmAllWindow);
    }

    @Override
    public DocumentInfoView upload(long projectId, byte[] file, String fileName, String category) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file, MediaType.APPLICATION_OCTET_STREAM).filename(fileName);
        body.part("projectId", projectId);
        if (category != null && !category.isBlank()) {
            body.part("category", category);
        }
        return RestClientInvoker.callMultipart(rest, "/internal/documents", body,
                new ParameterizedTypeReference<Response<DocumentInfoView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public PageView<DocumentInfoView> list(long projectId, Integer status, String keyword, String category,
                                           int page, int size, String sortBy, String sortDir) {
        StringBuilder uri = new StringBuilder("/internal/documents?projectId=").append(projectId);
        if (status != null) {
            uri.append("&status=").append(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            uri.append("&keyword=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        if (category != null && !category.isBlank()) {
            uri.append("&category=").append(URLEncoder.encode(category.trim().toUpperCase(),
                    StandardCharsets.UTF_8));
        }
        uri.append("&page=").append(page).append("&size=").append(size);
        if (sortBy != null && !sortBy.isBlank()) {
            uri.append("&sortBy=").append(URLEncoder.encode(sortBy, StandardCharsets.UTF_8));
        }
        if (sortDir != null && !sortDir.isBlank()) {
            uri.append("&sortDir=").append(URLEncoder.encode(sortDir, StandardCharsets.UTF_8));
        }
        return call(HttpMethod.GET, uri.toString(), null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public DocumentInfoView get(long id) {
        return call(HttpMethod.GET, "/internal/documents/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/documents/" + id, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public DocumentInfoView updateCategory(long id, DocumentCategoryRequest request) {
        return call(HttpMethod.PATCH, "/internal/documents/" + id + "/category", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DocumentInfoView updateUsageSwitches(long id, DocumentUsageSwitchesRequest request) {
        return call(HttpMethod.PATCH, "/internal/documents/" + id + "/usage-switches", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DocumentInfoView extract(long id, ExtractRequest request) {
        return call(HttpMethod.POST, "/internal/documents/" + id + "/extract", request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DocumentInfoView cancelExtraction(long id) {
        return call(HttpMethod.POST, "/internal/documents/" + id + "/extraction-cancel", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ExtractionPreviewView extraction(long id, int page, int size) {
        return callReadOnly("/internal/documents/" + id + "/extraction?page="
                + page + "&size=" + size, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<ExtractionEventView> extractionEvents(long id, long cursor, Integer limit) {
        String url = "/internal/documents/" + id + "/extraction-events?cursor=" + cursor
                + (limit == null || limit <= 0 ? "" : "&limit=" + limit);
        return callReadOnly(url, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<ExtractionEventView> specExtractionEvents(long id, long cursor, Integer limit) {
        String url = "/internal/documents/" + id + "/spec-extraction-events?cursor=" + cursor
                + (limit == null || limit <= 0 ? "" : "&limit=" + limit);
        return callReadOnly(url, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<DocumentChunkView> chunks(long id) {
        return call(HttpMethod.GET, "/internal/documents/" + id + "/chunks", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ChunkContextView chunkContext(long id, long chunkId) {
        return call(HttpMethod.GET, "/internal/documents/" + id
                + "/chunks/" + chunkId + "/context", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ConfirmExtractionResultView confirmExtraction(long id, ConfirmExtractionRequest request) {
        return call(HttpMethod.POST, "/internal/documents/" + id + "/confirm-extraction",
                request, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ConfirmExtractionResultView confirmAllExtraction(long id) {
        try {
            return RestClientInvoker.call(confirmAllRest, HttpMethod.POST,
                    "/internal/documents/" + id + "/confirm-all-extraction", null,
                    new ParameterizedTypeReference<Response<ConfirmExtractionResultView>>() {
                    }, UserContext.currentUserId());
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw confirmAllTimeoutRejection(id);
            }
            throw ex;
        }
    }

    /** Read window actually applied to the full-store leg (contract-tested). */
    Duration confirmAllForwardWindow() {
        return confirmAllWindow;
    }

    /**
     * Rejection once the full-store forward window is exceeded: names the cause, the
     * current budget and the adjustable key, and states the replay-safety contract
     * (already-landed items skip, nothing is written twice) so a retry click is safe.
     */
    ServiceException confirmAllTimeoutRejection(long documentId) {
        return new ServiceException(
                "全部入库本轮未在 " + confirmAllWindow.toSeconds() + " 秒转发时限内返回"
                        + "（服务层可能仍在继续入库；时限可经启动配置 "
                        + "ai.document.confirm-all-forward-read-timeout-seconds 调整，重启生效）。"
                        + "稍后重新点击「全部入库并生成用例」即可续上，已入库条目自动跳过、不会重复写入",
                BizErrorCode.SERVICE_UNAVAILABLE);
    }

    private static boolean hasTimeoutCause(Throwable ex) {
        for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }

    /**
     * Read-only observation face (extraction status probe + process-event
     * window): core answers these two GETs without ever reading the operator,
     * and the project-ownership gate sits single-sourced on the admin door
     * (assertCanView on the signed-in request thread). The live-tail pump
     * invokes them from a virtual thread where the login ThreadLocal is
     * empty, so resolving currentUserId() here would fail the read with a
     * false 401. Write methods keep forwarding the signed-in operator
     * verbatim — only these anonymous GETs opt out.
     */
    private <T> T callReadOnly(String uri, ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null, type, null);
    }
}
