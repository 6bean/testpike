package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.admin.aichat.api.dto.ChatFrameView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectLanding;
import com.sixbean.testpike.admin.aichat.api.dto.ChatExportMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSedimentMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatRunView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionPageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftApplyReceiptView;
import com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * RestClient implementation of the core dialog door (RestCoreHelpClient pattern;
 * call logic reused from {@link RestClientInvoker} — no second copy).
 * <p>The ledger crosses HTTP with the frame type as its enum name (STATE/DONE),
 * while the SSE contract speaks wire names (state/done): this boundary
 * normalises lowercase exactly once so the pump never maps ad hoc. The abort
 * call carries the operator header — core re-checks session ownership there.
 */
@Component
public class RestCoreAiChatClient implements CoreAiChatClient {

    private final RestClient rest;

    public RestCoreAiChatClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ChatSessionView createSession(long projectId, long userId, String title, int source,
                                         String contextJson) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/ai-chat/sessions",
                new SessionCreatePayload(projectId, userId, title, source, contextJson),
                new ParameterizedTypeReference<Response<ChatSessionView>>() {
                }, userId);
    }

    @Override
    public long startRun(long sessionId, long userId, String prompt,
                         List<ChatObjectRef> refObjects,
                         List<ChatImageAttachment> attachments,
                         boolean systemManager, String questionContext) {
        ChatRunView run = RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/sessions/" + sessionId + "/runs",
                new StartRunPayload(userId, prompt, refObjects, attachments, systemManager,
                        questionContext),
                new ParameterizedTypeReference<Response<ChatRunView>>() {
                }, userId);
        if (run == null) {
            throw new IllegalStateException("core 开单未返回运行句柄：sessions/" + sessionId + "/runs");
        }
        return run.assistantMessageId();
    }

    @Override
    public List<ChatFrameView> replay(long sessionId, long userId, long cursor) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/replay")
                .queryParam("cursor", cursor)
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return normalise(RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<List<ChatFrameView>>>() {
                }, userId));
    }

    @Override
    public List<ChatFrameView> replayRun(long userId, long messageId, long cursor) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/messages/" + messageId + "/replay")
                .queryParam("cursor", cursor)
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return normalise(RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<List<ChatFrameView>>>() {
                }, userId));
    }

    /** Null-safe wire-name normalisation over a core batch response. */
    private static List<ChatFrameView> normalise(List<ChatFrameView> frames) {
        if (frames == null) {
            return List.of();
        }
        return frames.stream().map(RestCoreAiChatClient::toWireName).toList();
    }

    @Override
    public ChatMessageView abort(long messageId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/messages/" + messageId + "/abort", null,
                new ParameterizedTypeReference<Response<ChatMessageView>>() {
                }, null);
    }

    @Override
    public ChatInjectLanding injectUser(long sessionId, long userId, String content) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/sessions/" + sessionId + "/injects",
                new InjectUserPayload(userId, content),
                new ParameterizedTypeReference<Response<ChatInjectLanding>>() {
                }, userId);
    }

    @Override
    public ChatMessageView recordFeedback(long messageId, long userId, int feedback, String reason) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/messages/" + messageId + "/feedback",
                new RecordFeedbackPayload(userId, feedback, reason),
                new ParameterizedTypeReference<Response<ChatMessageView>>() {
                }, userId);
    }

    @Override
    public List<ChatMessageView> listHistory(long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/messages")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        List<ChatMessageView> messages = RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<List<ChatMessageView>>>() {
                }, userId);
        return messages == null ? List.of() : messages;
    }

    @Override
    public ChatMessageView activeMessage(long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/active-message")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        // A null payload is the honest "nothing is running" verdict — never a
        // fake pointer, never an empty-list pretending success.
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<ChatMessageView>>() {
                }, userId);
    }

    @Override
    public ChatSessionPageMirrorView listSessions(long userId, List<Long> projectIds, Integer status,
                                                  Integer source, String keyword, int page, int pageSize) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions")
                .queryParam("userId", userId)
                .queryParam("page", page)
                .queryParam("pageSize", pageSize);
        if (projectIds != null && !projectIds.isEmpty()) {
            builder.queryParam("projectIds", projectIds.toArray());
        }
        if (status != null) {
            builder.queryParam("status", status);
        }
        if (source != null) {
            builder.queryParam("source", source);
        }
        if (keyword != null && !keyword.isBlank()) {
            builder.queryParam("keyword", keyword.strip());
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<ChatSessionPageMirrorView>>() {
                }, userId);
    }

    @Override
    public ChatSessionUsagePageMirrorView usageAttribution(String month, Long projectId,
                                                           int page, int pageSize) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/usage-attribution")
                .queryParam("month", month)
                .queryParam("page", page)
                .queryParam("pageSize", pageSize);
        if (projectId != null) {
            builder.queryParam("projectId", projectId);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<ChatSessionUsagePageMirrorView>>() {
                }, null);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView evolutionSignals(
            String month, Long projectId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/ai-chat/evolution-signals")
                .queryParam("month", month);
        if (projectId != null) {
            builder.queryParam("projectId", projectId);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView>>() {
                }, null);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView evolutionTrend(
            String month, Long projectId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/ai-chat/evolution-trend")
                .queryParam("month", month);
        if (projectId != null) {
            builder.queryParam("projectId", projectId);
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(), null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView>>() {
                }, null);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView generateEvolutionProposals(
            String month, Long projectId, long operatorId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("month", month);
        body.put("projectId", projectId);
        body.put("operatorId", operatorId);
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/evolution-proposals", body,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView>>() {
                }, null);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView
            rollbackEvolutionProposal(long draftId, long operatorId) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("operatorId", operatorId);
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/evolution-proposals/" + draftId + "/rollback", body,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView>>() {
                }, operatorId);
    }

    @Override
    public ChatSessionView renameSession(long sessionId, long userId, String title) {
        return RestClientInvoker.call(rest, HttpMethod.PATCH,
                "/internal/ai-chat/sessions/" + sessionId,
                new RenameSessionPayload(userId, title),
                new ParameterizedTypeReference<Response<ChatSessionView>>() {
                }, userId);
    }

    @Override
    public ChatSessionView archiveSession(long sessionId, long userId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/sessions/" + sessionId + "/archive?userId=" + userId, null,
                new ParameterizedTypeReference<Response<ChatSessionView>>() {
                }, userId);
    }

    @Override
    public ChatExportMirrorView exportSession(long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/export")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<ChatExportMirrorView>>() {
                }, userId);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.ChatContextWaterlineMirrorView contextWaterline(
            long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath(
                        "/internal/ai-chat/sessions/" + sessionId + "/context-waterline")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<
                        com.sixbean.testpike.admin.aichat.api.dto.ChatContextWaterlineMirrorView>>() {
                }, userId);
    }

    @Override
    public ChatSedimentMirrorView sedimentSession(long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/sediment")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.POST, uri, null,
                new ParameterizedTypeReference<Response<ChatSedimentMirrorView>>() {
                }, userId);
    }

    @Override
    public long undoSession(long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/undo")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        Long cardId = RestClientInvoker.call(rest, HttpMethod.POST, uri, null,
                new ParameterizedTypeReference<Response<Long>>() {
                }, userId);
        if (cardId == null) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "撤销草案登记失败（core 未返回草案编号）",
                    com.sixbean.testpike.common.exception.BizErrorCode.RESOURCE_CONFLICT);
        }
        return cardId;
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView createShare(
            long sessionId, long userId, int ttlHours) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/share")
                .queryParam("userId", userId)
                .queryParam("ttlHours", ttlHours)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.POST, uri, null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView>>() {
                }, userId);
    }

    @Override
    public java.util.List<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView> listShares(
            long sessionId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/ai-chat/sessions/" + sessionId + "/shares")
                .queryParam("userId", userId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<Response<java.util.List<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView>>>() {
                }, userId);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView revokeShare(
            long shareId, long userId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/shares/" + shareId + "/revoke?userId=" + userId, null,
                new ParameterizedTypeReference<Response<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView>>() {
                }, userId);
    }

    @Override
    public DraftTicketView confirmDraft(long draftId, long userId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/" + draftId + "/confirm-token", null,
                new ParameterizedTypeReference<Response<DraftTicketView>>() {
                }, userId);
    }

    @Override
    public DraftApplyReceiptView applyDraft(long draftId, long userId, String confirmToken,
                                            String selectedAction) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/" + draftId + "/apply",
                new ApplyDraftPayload(confirmToken, selectedAction),
                new ParameterizedTypeReference<Response<DraftApplyReceiptView>>() {
                }, userId);
    }

    @Override
    public int updateCheckedSubset(long draftId, long userId, List<String> names) {
        Integer checked = RestClientInvoker.call(rest, HttpMethod.PATCH,
                "/internal/ai-chat/drafts/" + draftId + "/checked-subset",
                new CheckedSubsetPayload(names),
                new ParameterizedTypeReference<Response<Integer>>() {
                }, userId);
        return checked == null ? 0 : checked;
    }

    @Override
    public int draftPendingCount(long projectId, String objectType, long targetId) {
        Integer count = RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/ai-chat/drafts/pending-count?projectId=" + projectId
                        + "&objectType=" + objectType + "&targetId=" + targetId,
                null,
                new ParameterizedTypeReference<Response<Integer>>() {
                }, null);
        return count == null ? 0 : count;
    }

    @Override
    public DraftTicketView confirmGroupDrafts(long messageId, long userId) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/group/" + messageId + "/confirm-token", null,
                new ParameterizedTypeReference<Response<DraftTicketView>>() {
                }, userId);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDrafts(
            long messageId, long userId, String confirmToken, java.util.List<Long> draftIds) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/group/" + messageId + "/apply",
                new ApplyGroupDraftPayload(confirmToken, draftIds),
                new ParameterizedTypeReference<
                        Response<com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView>>() {
                }, userId);
    }

    @Override
    public DraftTicketView confirmGroupDraftsByScope(long userId, String groupScope) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/group/confirm-token",
                new GroupScopeTicketPayload(groupScope),
                new ParameterizedTypeReference<Response<DraftTicketView>>() {
                }, userId);
    }

    @Override
    public com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDraftsByScope(
            long userId, String groupScope, String confirmToken, java.util.List<Long> draftIds) {
        return RestClientInvoker.call(rest, HttpMethod.POST,
                "/internal/ai-chat/drafts/group/apply",
                new ApplyGroupScopePayload(groupScope, confirmToken, draftIds),
                new ParameterizedTypeReference<
                        Response<com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView>>() {
                }, userId);
    }

    @Override
    public java.util.List<com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView> groupPendingCards(
            long userId, String groupScope) {
        java.util.List<com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView> cards =
                RestClientInvoker.call(rest, HttpMethod.GET,
                        "/internal/ai-chat/drafts/group/pending?scope=" + groupScope, null,
                        new ParameterizedTypeReference<Response<
                                java.util.List<com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView>>>() {
                        }, userId);
        return cards == null ? java.util.List.of() : cards;
    }

    @Override
    public int readConfigInt(String key, int defaultValue) {
        try {
            String raw = RestClientInvoker.call(rest, HttpMethod.GET,
                    "/internal/configs/value?key=" + key + "&defaultValue=", null,
                    new ParameterizedTypeReference<Response<String>>() {
                    }, null);
            return raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
        } catch (Exception unreadable) {
            // K8 door down or value malformed: the pump keeps its documented default.
            return defaultValue;
        }
    }

    @Override
    public String readConfigString(String key, String defaultValue) {
        try {
            // Same door as readConfigInt; the raw value stays a string so the
            // comma-list shape survives transport untouched.
            String raw = RestClientInvoker.call(rest, HttpMethod.GET,
                    "/internal/configs/value?key=" + key + "&defaultValue=", null,
                    new ParameterizedTypeReference<Response<String>>() {
                    }, null);
            return raw == null || raw.isBlank() ? defaultValue : raw.trim();
        } catch (Exception unreadable) {
            // K8 door down or value malformed: the caller keeps its documented default.
            return defaultValue;
        }
    }

    /** Ledger enum name → SSE wire name (the one and only normalisation point). */
    private static ChatFrameView toWireName(ChatFrameView frame) {
        String wire = frame.eventType() == null ? null : frame.eventType().toLowerCase();
        return new ChatFrameView(frame.id(), frame.sessionId(), frame.messageId(), frame.seq(),
                wire, frame.payload(), frame.metaJson());
    }
}
