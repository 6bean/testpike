package com.sixbean.testpike.api.ci.domain.component;

import com.sixbean.testpike.api.ci.api.dto.CiRunInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Set;


@Component
public class CiRunValidator {

    
    static final Set<String> OVERRIDE_KEYS = Set.of("url", "accountRef");

    
    public void validate(CiRunInput input) {
        if (input == null) {
            throw new ServiceException("请求体不能为空：须提供 batch（批次 id）或"
                    + "profile（批次模板名，配 projectId）二选一", BizErrorCode.VALIDATION_ERROR);
        }
        boolean hasBatch = input.batch() != null;
        boolean hasProfile = input.profile() != null && !input.profile().isBlank();
        if (!hasBatch && !hasProfile) {
            throw new ServiceException("batch 与 profile 二选一：batch=已有草稿批次 id，"
                    + "profile=批次模板名（须配 projectId）", BizErrorCode.VALIDATION_ERROR);
        }
        if (hasBatch && hasProfile) {
            throw new ServiceException("batch 与 profile 二选一，不能同时提供：用 batch 直接触发"
                    + "已有批次，或用 profile 从模板新建批次", BizErrorCode.VALIDATION_ERROR);
        }
        if (hasProfile && input.projectId() == null) {
            throw new ServiceException("profile 链路必须携带 projectId（模板按项目解析）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envOverrides() != null) {
            for (String key : input.envOverrides().keySet()) {
                if (!OVERRIDE_KEYS.contains(key)) {
                    throw new ServiceException("envOverrides 含不支持的键「" + key
                            + "」：合法键仅 url（覆盖执行基址）与 accountRef（凭据引用）",
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
    }
}
