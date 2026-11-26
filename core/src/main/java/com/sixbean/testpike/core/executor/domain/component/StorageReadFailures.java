package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Shared error mapping for object-read failures across storage providers
 * (S3-compatible gateway / Aliyun OSS). Extracted as the single source when the
 * second provider consumed the same mapping semantics (R40): a missing object is
 * a user-facing 40401 with the key named; everything else stays an internal error
 * carrying the upstream message. Providers with different exception taxonomies
 * normalize into these two factories instead of repeating the text.
 */
final class StorageReadFailures {

    private StorageReadFailures() {
    }

    /** Common write-side guard: an empty object key never reaches any provider. */
    static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ServiceException("对象 key 不能为空——本次请求未携带产物文件路径，"
                    + "请返回「执行中心」重新打开该任务的产物，仍失败则重新发起一次运行",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    static ServiceException notFound(String key) {
        return new ServiceException("对象不存在: " + key + "——产物可能已按留存周期自动清理，"
                + "请在「执行中心」对该任务重新发起运行生成新产物", BizErrorCode.NOT_FOUND);
    }

    
    static ServiceException readFailure(Exception e) {
        return new ServiceException("对象读取失败: " + e.getMessage() + "——存储网关暂时不可达，"
                + "可在「执行中心」稍后重新打开该产物，持续失败请查看「执行器池」节点自检报告",
                BizErrorCode.INTERNAL_ERROR);
    }

    /**
     * One exit for read failures across provider SDKs: pass-through for errors that
     * are already user-facing, 40401 when the caller normalized the SDK-specific
     * "missing object" taxonomy into {@code missing}, internal error otherwise.
     */
    /**
     * Existence probe guard shared by providers: a blank key never exists, and any
     * probe failure is treated as absent (idempotent-cleanup semantics callers rely
     * on) instead of surfacing an internal error.
     */
    static boolean existsGuarded(String key, BooleanSupplier probe, Logger log, String label) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            return probe.getAsBoolean();
        } catch (Exception e) {
            log.debug("{} existence probe failed, treated as absent: key={} err={}",
                    label, key, e.getMessage());
            return false;
        }
    }

    static ServiceException fromReadException(Exception e, String key, boolean missing) {
        if (e instanceof ServiceException se) {
            return se;
        }
        return missing ? notFound(key) : readFailure(e);
    }
}
