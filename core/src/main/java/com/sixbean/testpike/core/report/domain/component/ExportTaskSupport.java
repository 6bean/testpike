package com.sixbean.testpike.core.report.domain.component;

import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;


@Component
public class ExportTaskSupport {

    private static final Logger log = LoggerFactory.getLogger(ExportTaskSupport.class);

    private static final String EXPORT_PREFIX = "export/";
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final JdbcTemplate jdbcTemplate;
    private final ArtifactStorage artifactStorage;
    private final NotificationService notificationService;
    private final ExportTaskExecutor exportTaskExecutor;

    public ExportTaskSupport(JdbcTemplate jdbcTemplate, ArtifactStorage artifactStorage,
                             NotificationService notificationService,
                             ExportTaskExecutor exportTaskExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactStorage = artifactStorage;
        this.notificationService = notificationService;
        this.exportTaskExecutor = exportTaskExecutor;
    }

    
    public long createTask(long operatorId, long projectId, String taskType, boolean masked, int rowCount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_export_task (project_id, report_type, masked, status, row_count, created_by)
                VALUES (?, ?, ?, 1, ?, ?) RETURNING id
                """, Long.class, projectId, taskType, masked ? 1 : 0, rowCount, operatorId);
    }

    
    public void startAsync(long taskId, long operatorId, long projectId, String fileCategory,
                           String notifyTitle, String notifyContentTpl,
                           Supplier<ExportArtifact> artifactSupplier) {
        exportTaskExecutor.submit(() -> {
            try {
                ExportArtifact artifact = artifactSupplier.get();
                String fileKey = EXPORT_PREFIX + projectId + "/" + fileCategory + "_"
                        + System.currentTimeMillis() + ".xlsx";
                artifactStorage.putBytes(fileKey, artifact.content(), XLSX_MIME);
                notificationService.create(new NotificationCreateInput(operatorId, projectId, "EXPORT_DONE",
                        notifyTitle,
                        String.format(notifyContentTpl, artifact.rowCount()),
                        "EXPORT_DONE:" + taskId));
                jdbcTemplate.update("""
                        UPDATE tp_export_task SET status = 2, file_key = ?, row_count = ?, finished_at = now()
                        WHERE id = ?
                        """, fileKey, artifact.rowCount(), taskId);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                jdbcTemplate.update("""
                        UPDATE tp_export_task SET status = 3, error_detail = ?, finished_at = now() WHERE id = ?
                        """, msg.length() > 500 ? msg.substring(0, 500) : msg, taskId);
                log.warn("异步导出任务失败: taskId={} err={}", taskId, msg);
            }
        });
    }
}
