package com.sixbean.testpike.common.aichat;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonicalisation of the dialog user-image attachments .
 * <p>This is the SINGLE validation point of the attachment channel, mirroring
 * the {@link ChatObjectRefs} posture: admin only mirrors the wire shape, core
 * canonicalises once when opening the run (a malformed entry refuses the run
 * with the offending value named BEFORE anything lands — a silently dropped
 * photo would make the answer differ from what the user attached), and
 * ai-service consumes the canonical list without re-checking.
 * <p>Rules pinned by {@code ChatImageAttachmentsTest}: null/empty input is a
 * legal empty channel (old-shape requests keep working, zero drift); blank or
 * malformed storage keys are refused by name; keys of any other storage scene
 * (e.g. exec-artifact, which only reaches the model through core's F-8 door)
 * are refused — the user-image channel reads chat-image keys only; duplicates
 * collapse by objectKey keeping the first arrival; the count cap refuses by
 * naming both numbers (never a truncation); an over-long file name is cut to
 * the display bound, and a missing label normalises to the empty string.
 * <p>The project-slice check is core's side of the door: {@link #projectIdOf}
 * reads the segment segment of a valid key so {@code ChatRunServiceImpl} can
 * refuse a key issued under another project (H1 red line — a photo upload is
 * project-scoped bytes, pointing at someone else's upload is horizontal
 * access).
 */
public final class ChatImageAttachments {

    /** Storage scene of this channel (StorageKeys.SCENES member). */
    public static final String SCENE = "chat-image";

    /**
 * Canonical key shape: [prefix/]projectId/chat-image/yyyyMM/uuid8-name.
 * The prefix segment may itself contain no slashes on today's deployments
 * but the pattern stays tolerant of one leading segment group; the segment,
 * scene, month and final segment are strict.
 */
    private static final Pattern KEY_SHAPE = Pattern.compile(
            "^(?:[^/]+/)?(\\d+)/" + SCENE + "/(\\d{6})/[^/?#]+$");

    /** Display bound of the file label — longer names are cut, not refused. */
    public static final int FILE_NAME_MAX_CHARS = 120;

    /** K8 key of the per-question image cap (hot-updatable; core resolves it). */
    public static final String KEY_MAX_IMAGES = "ai.chat.image.max-attachments";
    /** Seeded default when the key is unreadable. */
    public static final int DEFAULT_MAX_IMAGES = 4;

    private ChatImageAttachments() {
    }

    /**
     * Validate + normalise one request's attachments.
     *
     * @param attachments raw wire list, may be null (old-shape request)
     * @param maxImages   hot-readable cap resolved by the caller
     * @return canonical list, insertion order kept, never null
     * @throws ServiceException naming the offending value or both cap numbers
     */
    public static List<ChatImageAttachment> canonicalize(List<ChatImageAttachment> attachments,
                                                         int maxImages) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (attachments.size() > maxImages) {
            throw new ServiceException("对话图片附件超出单条问题上限（本次 " + attachments.size()
                    + " 枚，上限 " + maxImages + " 枚），请减少图片后重新发送",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ChatImageAttachment> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatImageAttachment attachment : attachments) {
            String key = attachment == null || attachment.objectKey() == null
                    ? "" : attachment.objectKey().trim();
            if (key.isEmpty()) {
                throw new ServiceException("图片附件缺少存储键（上传未成功时不应发送该附件）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            Matcher matcher = KEY_SHAPE.matcher(key);
            if (!matcher.matches()) {
                throw new ServiceException("图片附件存储键不符合对话图片通道格式：" + key,
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (seen.add(key)) {
                out.add(new ChatImageAttachment(key, label(attachment.fileName())));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Read the project-slice segment of a canonical key.
     *
     * @throws ServiceException naming the key when it is not a chat-image key
     */
    public static long projectIdOf(String objectKey) {
        String key = objectKey == null ? "" : objectKey.trim();
        Matcher matcher = KEY_SHAPE.matcher(key);
        if (!matcher.matches()) {
            throw new ServiceException("图片附件存储键不符合对话图片通道格式：" + key,
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String label(String fileName) {
        String trimmed = fileName == null ? "" : fileName.trim();
        return trimmed.length() <= FILE_NAME_MAX_CHARS
                ? trimmed : trimmed.substring(0, FILE_NAME_MAX_CHARS);
    }
}
