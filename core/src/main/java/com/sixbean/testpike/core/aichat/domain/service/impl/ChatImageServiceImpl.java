package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.aichat.ChatImageAttachments;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.common.storage.UploadFileTypes;
import com.sixbean.testpike.core.aichat.api.dto.ChatImageInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatImageService;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Chat user-image upload implementation (AO batch P4 , real
 * dev DB + storage proven by {@code ChatImageUploadServiceTest}).
 * <p>Order of operations is the whole safety story: the project segment must
 * exist first (an upload into a phantom project has no owner to enforce H1
 * against later), then {@link UploadFileTypes} runs the size/extension/magic
 * triple gate — the SINGLE validation point, admin mirrors nothing — and
 * only after both pass does {@link StorageKeys#build} mint a canonical
 * chat-image key under the issuing project and the bytes land. A refusal
 * therefore never leaves an orphan object, and every key this door mints is
 * accepted by {@link ChatImageAttachments} at the run door unchanged.
 * <p>Provider choice follows the comment-attachment precedent: route through
 * the system default provider (local/s3/oss transparent per storage
 * config) with the project encoded in the key itself — no per-scene second
 * routing table.
 */
@Service
public class ChatImageServiceImpl implements ChatImageService {

    private final JdbcTemplate jdbcTemplate;
    private final StorageRouter storageRouter;

    public ChatImageServiceImpl(JdbcTemplate jdbcTemplate, StorageRouter storageRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageRouter = storageRouter;
    }

    @Override
    public ChatImageInfo upload(long projectId, String fileName, String declaredContentType,
                                byte[] content) {
        Long hostProject = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_project WHERE id = ?", Long.class, projectId);
        if (hostProject == null || hostProject == 0) {
            throw new ServiceException("项目不存在，无法上传对话图片（项目 id：" + projectId + "）——请将顶栏切换到已有项目后，在「AI 对话」重新上传",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String mime = UploadFileTypes.validateAttachment(fileName, declaredContentType, content);
        String objectKey = StorageKeys.build(storageRouter.keyPrefix(), projectId,
                ChatImageAttachments.SCENE, fileName);
        storageRouter.systemProvider().put(objectKey, content, mime);
        return new ChatImageInfo(objectKey, basename(fileName), mime,
                content == null ? 0 : content.length);
    }

    /** Display label = basename only (path segments stripped, XSS surface kept closed). */
    private static String basename(String fileName) {
        String base = fileName == null ? "" : fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String trimmed = base.trim();
        return trimmed.isEmpty() ? "image" : trimmed;
    }

    @Override
    public String presignReadUrl(long projectId, String objectKey) {
        // Shape first: ChatImageAttachments refuses non-chat-image keys by
        // name (exec-artifact and every other scene stays out of this door).
        long owner = ChatImageAttachments.projectIdOf(objectKey);
        if (owner != projectId) {
            throw new ServiceException("对话图片不属于当前项目，无法读取（该图片由项目 id "
                    + owner + " 上传）——请将顶栏切换到该图片所属项目，或在「AI 对话」重新上传本项目的图片", BizErrorCode.VALIDATION_ERROR);
        }
        if (!storageRouter.systemProvider().exists(objectKey)) {
            throw new ServiceException("对话图片不存在或已被清理，无法读取（存储键：" + objectKey + "）——请在「AI 对话」重新粘贴截图或选择本地图片后发送",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return storageRouter.systemProvider()
                .presignGet(objectKey, storageRouter.urlTtl()).url();
    }
}
