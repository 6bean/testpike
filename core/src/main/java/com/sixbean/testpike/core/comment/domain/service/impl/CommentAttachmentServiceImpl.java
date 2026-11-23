package com.sixbean.testpike.core.comment.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.common.storage.UploadFileTypes;
import com.sixbean.testpike.core.comment.api.dto.CommentAttachmentInfo;
import com.sixbean.testpike.core.comment.domain.service.CommentAttachmentService;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class CommentAttachmentServiceImpl implements CommentAttachmentService {

    
    static final String SCENE = "comment-attachment";

    private final JdbcTemplate jdbcTemplate;
    private final StorageRouter storageRouter;

    public CommentAttachmentServiceImpl(JdbcTemplate jdbcTemplate, StorageRouter storageRouter) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageRouter = storageRouter;
    }

    @Override
    public CommentAttachmentInfo upload(long commentId, String fileName, String declaredContentType, byte[] content) {
        Long hostComment = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_comment WHERE id = ?", Long.class, commentId);
        if (hostComment == null || hostComment == 0) {
            throw new ServiceException("评论不存在（附件须挂在已创建评论上）", BizErrorCode.VALIDATION_ERROR);
        }
        String mime = UploadFileTypes.validateAttachment(fileName, declaredContentType, content);
        String objectKey = StorageKeys.build(storageRouter.keyPrefix(), 0L, SCENE, fileName);
        storageRouter.systemProvider().put(objectKey, content, mime);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_comment_attachment (comment_id, file_name, object_key, content_type, size_bytes)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, commentId, safeName(fileName), objectKey, mime, (long) content.length);
        return rowView(id == null ? -1L : id);
    }

    @Override
    public List<CommentAttachmentInfo> listByComment(long commentId) {
        return jdbcTemplate.query("""
                SELECT id, comment_id, file_name, object_key, content_type, size_bytes,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_comment_attachment WHERE comment_id = ? ORDER BY id
                """, (rs, n) -> new CommentAttachmentInfo(rs.getLong("id"), rs.getLong("comment_id"),
                rs.getString("file_name"), rs.getString("object_key"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("created_at")), commentId);
    }

    @Override
    public StorageGrant download(long attachmentId) {
        String objectKey = jdbcTemplate.query("""
                SELECT object_key FROM tp_comment_attachment WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, attachmentId);
        if (objectKey == null) {
            throw new ServiceException("附件不存在", BizErrorCode.NOT_FOUND);
        }
        return storageRouter.systemProvider().presignGet(objectKey, storageRouter.urlTtl());
    }

    private CommentAttachmentInfo rowView(long id) {
        return jdbcTemplate.query("""
                SELECT id, comment_id, file_name, object_key, content_type, size_bytes,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_comment_attachment WHERE id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new ServiceException("附件写入后不可见（内部一致性异常）", BizErrorCode.INTERNAL_ERROR);
            }
            return new CommentAttachmentInfo(rs.getLong("id"), rs.getLong("comment_id"),
                    rs.getString("file_name"), rs.getString("object_key"), rs.getString("content_type"),
                    rs.getLong("size_bytes"), rs.getString("created_at"));
        }, id);
    }

    
    private String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        return base.length() <= 255 ? base : base.substring(base.length() - 255);
    }
}
