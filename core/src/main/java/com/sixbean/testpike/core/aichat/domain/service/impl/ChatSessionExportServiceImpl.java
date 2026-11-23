package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionExportService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Markdown renderer for one dialog session . The
 * ledger stays the single source: turns come from {@link
 * ChatFrameService#listSealedMessages} — already ownership-checked and with
 * streamed answers rebuilt from delta frames — so the exported file is
 * byte-for-byte what the user watched live, minus what must never travel:
 * every text block passes the shared masking chain (phone/email plus the
 * credential-shape rules) before it reaches the page, and internal ledger ids
 * (message/session keys, frame cursors) stay inside the ledger.
 * <p>System-role rows are conversation plumbing (routing notes, frame
 * bookkeeping), not dialog, so they never count as turns and never print.
 */
@Service
public class ChatSessionExportServiceImpl implements ChatSessionExportService {

    /** Turn labels of the Q/A structure — the same question/answer words the
 * session panel renders . */
    private static final String LABEL_QUESTION = "问";
    private static final String LABEL_ANSWER = "答";

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** On-screen stamp shape: the share snapshot renders this line, so an
     *  undelimited stamp (20261030-1404) would be unreadable copy. */
    private static final DateTimeFormatter RENDER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChatSessionService sessionService;
    private final ChatFrameService frameService;

    public ChatSessionExportServiceImpl(ChatSessionService sessionService,
                                        ChatFrameService frameService) {
        this.sessionService = sessionService;
        this.frameService = frameService;
    }

    @Override
    public ChatExportView renderMarkdown(long userId, long sessionId) {
        // requireOwned here aims the door; listSealedMessages re-checks through
        // the same single-sourced guard — two callers, one ownership rule.
        ChatSessionInfo session = sessionService.requireOwned(sessionId, userId);
        List<ChatMessageInfo> turns = frameService.listSealedMessages(userId, sessionId);

        StringBuilder out = new StringBuilder();
        String title = blankToDefault(session.title(), "AI 对话记录");
        out.append("# ").append(title).append("\n\n");
        out.append("生成于 ")
                .append(java.time.LocalDateTime.now().format(RENDER_STAMP)).append("\n\n");

        int round = 0;
        int questionTurns = 0;
        for (ChatMessageInfo turn : turns) {
            if ("system".equals(turn.role())) {
                continue;
            }
            boolean question = "user".equals(turn.role());
            if (question) {
                round++;
                questionTurns++;
            }
            out.append("## ").append(question ? LABEL_QUESTION : LABEL_ANSWER)
                    .append(" · 第 ").append(round).append(" 轮\n\n");
            String body = DesensitizeUtils.maskSecrets(
                    DesensitizeUtils.maskText(blankToDefault(turn.content(), "")));
            out.append(body).append("\n\n");
        }

        return new ChatExportView(sessionId, title, out.toString(),
                buildFilename(title), questionTurns);
    }

    /** Download name: masked-title characters kept readable, ids never leak into names. */
    private static String buildFilename(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        if (cleaned.isBlank()) {
            // Fallback token must stay ASCII-safe: a Chinese default title in a
            // download name would break filesystems that reject non-ASCII bytes.
            cleaned = "AI-chat-export";
        }
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        // The export stamp is the render instant: the session row carries no
        // created_at on its view, and a guessed open-time in a download name
        // would be a fabricated number.
        return cleaned + "-" + java.time.LocalDateTime.now().format(FILE_STAMP) + ".md";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
