package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.regex.Pattern;


public final class UserValidationRules {

    
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");
    
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private UserValidationRules() {
    }

    
    public static void validatePassword(String password, String label) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new ServiceException(label + "需为 8~64 位且同时包含字母和数字",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public static void validateRole(int role) {
        if (role < 1 || role > 4) {
            throw new ServiceException("角色仅支持 1=测试人员 2=测试负责人 3=开发人员 4=系统管理员",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public static boolean isValidEmail(String email) {
        return email == null || EMAIL_PATTERN.matcher(email).matches();
    }

    
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
