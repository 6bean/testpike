package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;


public final class BrowserKernelSupport {

    
    public static final Set<String> KERNELS = Set.of("chromium", "firefox", "webkit");

    
    public static final String DEFAULT_KERNEL = "chromium";

    private BrowserKernelSupport() {
    }

    
    public static String resolve(String requested, ProjectEnvInfo env) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_KERNEL;
        }
        String value = requested.trim().toLowerCase();
        if (!KERNELS.contains(value)) {
            throw new ServiceException("浏览器 " + requested + " 不受支持，平台仅支持 "
                    + "chromium / firefox / webkit——请在发起执行弹窗的「浏览器」下拉改选其一后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (env == null) {
            return value;
        }
        Set<String> matrix = Arrays.stream((env.browsers() == null ? "" : env.browsers()).split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        if (!matrix.contains(value)) {
            throw new ServiceException("所选环境「" + env.name() + "」的浏览器矩阵（"
                    + (matrix.isEmpty() ? DEFAULT_KERNEL : env.browsers()) + "）不包含 " + value
                    + "——请在「项目」详情页的环境列表中编辑该环境，把「浏览器」字段补上 " + value
                    + "（逗号分隔）并保存，再重新发起执行", BizErrorCode.VALIDATION_ERROR);
        }
        return value;
    }
}
