package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ArtifactGrantView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ArtifactStorageProjectRouteTest {

    @Autowired
    private ArtifactStorageTestHarness harness;

    
    @Test
    void mode2项目_grant落本地路由_前缀叠加且view经反解走本地() throws Exception {
        String tag = harness.uniq();
        long taskId = 0;
        try {
            taskId = harness.seedTask(2, "/rec-" + tag, tag);
            ArtifactGrantView grant = harness.artifactStorage().grantUpload(taskId, "screenshot", "m2.png");

            assertTrue(grant.objectKey().startsWith("testpike/rec-" + tag + "/" + taskId
                            + "/exec-artifact/" + harness.ym() + "/"),
                    "mode=2 key 须为 全局前缀/项目路径/taskId/exec-artifact：...: " + grant.objectKey());
            assertTrue(grant.objectKey().matches(".*/[0-9a-f]{8}-screenshot_m2\\.png"),
                    "文件名清洗段不变: " + grant.objectKey());
            assertTrue(grant.uploadUrl().contains("/storage/files/"),
                    "本地模式预签名须指向 admin 反代端点: " + grant.uploadUrl());
            assertTrue(grant.uploadUrl().contains("signature="), "带签 URL: " + grant.uploadUrl());

            harness.assertLocalProviderRoundTrip(grant.objectKey(), new byte[]{1, 2, 3},
                    "本地对象存在性确认须经本地 Provider（s3 上必不存在）", "查看 URL 走本地反代: ");
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void mode3项目_系统s3_落云前缀正确路径() {
        String tag = harness.uniq();
        try {
            long taskId = harness.seedTask(3, "/rec-" + tag, tag);
            ArtifactGrantView grant = harness.artifactStorage().grantUpload(taskId, "video", "run.webm");
            assertTrue(grant.objectKey().startsWith("testpike/rec-" + tag + "/" + taskId
                    + "/exec-artifact/"), "mode=3 key 前缀叠加: " + grant.objectKey());
            assertTrue(grant.uploadUrl().startsWith(harness.urlExpectations().presignedPrefix()),
                    "mode=3 系统=s3 须落云桶: " + grant.uploadUrl());
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void mode1默认项目_行为与存量一致_无路径零变化() {
        String tag = harness.uniq();
        try {
            long taskId = harness.seedTask(1, null, tag);
            ArtifactGrantView grant = harness.artifactStorage().grantUpload(taskId, "screenshot", "m1.png");
            assertTrue(grant.objectKey().startsWith("testpike/" + taskId + "/exec-artifact/" + harness.ym() + "/"),
                    "默认项目 key 规范不变（prefix/taskId/scene）: " + grant.objectKey());
            assertTrue(grant.uploadUrl().startsWith(harness.urlExpectations().presignedPrefix()),
                    "默认项目走系统 provider: " + grant.uploadUrl());
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void 项目路径穿越_grant拒绝人话点名() {
        String tag = harness.uniq();
        try {
            long taskId = harness.seedTask(2, "/bad/../escape", tag);
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> harness.artifactStorage().grantUpload(taskId, "screenshot", "x.png"));
            assertTrue(ex.getMessage().contains("项目存储路径非法"), "人话点名: " + ex.getMessage());
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void 任务不存在_回退系统默认_零破坏() {
        ArtifactGrantView grant = harness.artifactStorage().grantUpload(999999901L, "screenshot", "legacy.png");
        assertTrue(grant.objectKey().matches("testpike/999999901/exec-artifact/\\d{6}/[0-9a-f]{8}-screenshot_legacy\\.png"),
                "悬空任务 key 规范不变: " + grant.objectKey());
        assertTrue(grant.uploadUrl().startsWith(harness.urlExpectations().presignedPrefix()),
                "回退系统 provider: " + grant.uploadUrl());
    }

    
    @Test
    void 存量非规范key_反解未命中直通系统Provider() {
        assertTrue(!harness.artifactStorage().exists("exec/777/screenshot/gone.png"),
                "旧前缀 key 反解未命中仍走系统 Provider（s3 上不存在=false 可判）");
        String view = harness.artifactStorage().grantViewUrl("exec/777/screenshot/a.png");
        assertTrue(view.startsWith(harness.urlExpectations().presignedPrefix()),
                "旧 key 查看直通系统预签名: " + view);
    }
}
