package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;


@Component
public class AppautoCatalogStatusRules {

    
    public void requireValidStatus(int status) {
        if (status != 0 && status != 1) {
            throw new ServiceException("状态仅支持 1=启用/0=停用（下架行退出用户可选面，管理侧仍可见可恢复），收到「"
                    + status + "」——请在「状态」输入中改成 1 或 0（示例：1=启用）", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
