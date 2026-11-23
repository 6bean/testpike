package com.sixbean.testpike.core.user.domain.component;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.user.api.dto.UserImportRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class UserExcelParser {

    
    public static final List<String> HEADERS =
            List.of("登录名", "姓名", "邮箱", "手机", "角色", "初始密码");

    
    public static final String HEADER_HINT =
            "登录名*（≤50 字符，全局唯一）| 姓名 | 邮箱 | 手机 | 角色(1=测试人员 2=测试负责人 3=开发人员 4=系统管理员，缺省 1) | 初始密码*（8~64 位含字母和数字）";

    public List<UserImportRow> parse(byte[] file, String fileName) {
        if (file == null || file.length == 0) {
            throw new ServiceException("导入文件为空，请先下载模板填写后上传", BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<Integer, String>> data;
        try {
            data = EasyExcel.read(new ByteArrayInputStream(file)).headRowNumber(1).sheet().doReadSync();
        } catch (Exception e) {
            throw new ServiceException("文件格式错误：请使用模板（.xlsx）导出的格式上传", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Integer> seenUsernames = new HashMap<>();
        List<UserImportRow> rows = new ArrayList<>();
        int rowNo = 2;
        for (Map<Integer, String> line : data) {
            if (line == null || line.isEmpty()) {
                rowNo++;
                continue;
            }
            rows.add(validate(rowNo, line, seenUsernames));
            rowNo++;
        }
        if (rows.isEmpty()) {
            throw new ServiceException("导入文件没有数据行（至少 1 条），请填写后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
        return rows;
    }

    private UserImportRow validate(int rowNo, Map<Integer, String> line, Map<String, Integer> seenUsernames) {
        String username = cell(line, 0);
        String displayName = cell(line, 1);
        String email = cell(line, 2);
        String phone = cell(line, 3);
        String roleRaw = cell(line, 4);
        String password = cell(line, 5);

        String failReason = null;
        if (username.isEmpty()) {
            failReason = "登录名不能为空";
        } else if (username.length() > 50) {
            failReason = "登录名超过 50 字符";
        } else {
            Integer dupRow = seenUsernames.putIfAbsent(username, rowNo);
            if (dupRow != null) {
                failReason = "登录名「" + username + "」与第 " + dupRow + " 行重复";
            }
        }
        if (failReason == null && !email.isEmpty() && !UserValidationRules.isValidEmail(email)) {
            failReason = "邮箱格式不正确：" + email;
        }
        Integer role = 1;
        if (failReason == null && !roleRaw.isEmpty()) {
            String rv = normalizeNumber(roleRaw);
            if (!rv.chars().allMatch(Character::isDigit)) {
                failReason = "角色仅支持 1/2/3/4 数字，实际: " + roleRaw;
            } else {
                role = Integer.parseInt(rv);
                if (role < 1 || role > 4) {
                    failReason = "角色仅支持 1=测试人员 2=测试负责人 3=开发人员 4=系统管理员，实际: " + roleRaw;
                }
            }
        }
        try {
            UserValidationRules.validatePassword(password, "初始密码");
        } catch (ServiceException e) {
            failReason = e.getMessage();
        }
        return new UserImportRow(rowNo, username.isEmpty() ? null : username,
                displayName.isEmpty() ? null : displayName,
                email.isEmpty() ? null : email,
                phone.isEmpty() ? null : phone,
                role, password, failReason);
    }

    
    private String normalizeNumber(String raw) {
        if (raw.endsWith(".0")) {
            String prefix = raw.substring(0, raw.length() - 2);
            if (!prefix.isEmpty() && prefix.chars().allMatch(Character::isDigit)) {
                return prefix;
            }
        }
        return raw;
    }

    private String cell(Map<Integer, String> line, int index) {
        String v = line.get(index);
        return v == null ? "" : v.trim();
    }
}
