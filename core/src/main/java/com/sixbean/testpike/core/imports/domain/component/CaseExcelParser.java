package com.sixbean.testpike.core.imports.domain.component;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.imports.api.dto.ImportCaseRow;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class CaseExcelParser {

    
    public static final List<String> HEADERS =
            List.of("测试目标", "前置条件", "预期结果", "优先级", "置信度", "终端类型", "步骤");

    private static final Set<String> END_TYPES = Set.of("web", "mobile", "pos", "multi");
    private static final Set<String> PRIORITIES = Set.of("0", "1", "2");
    private static final Set<String> CONFIDENCES = Set.of("1", "2", "3");

    public List<ImportCaseRow> parse(byte[] file, String fileName) {
        if (file == null || file.length == 0) {
            throw new ServiceException("导入文件为空，请先下载模板填写后上传", BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<Integer, String>> data;
        try {
            data = EasyExcel.read(new ByteArrayInputStream(file)).headRowNumber(1).sheet().doReadSync();
        } catch (Exception e) {
            throw new ServiceException("文件格式错误：请使用模板（.xlsx）导出的格式上传", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Integer> seenTitles = new HashMap<>();
        List<ImportCaseRow> rows = new ArrayList<>();
        int rowNo = 2;
        for (Map<Integer, String> line : data) {
            if (line == null || line.isEmpty()) {
                rowNo++;
                continue;
            }
            rows.add(validate(rowNo, line, seenTitles));
            rowNo++;
        }
        if (rows.isEmpty()) {
            throw new ServiceException("导入文件没有数据行（至少 1 条），请填写后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
        return rows;
    }

    private ImportCaseRow validate(int rowNo, Map<Integer, String> line, Map<String, Integer> seenTitles) {
        String title = cell(line, 0);
        String precondition = cell(line, 1);
        String expectedResult = cell(line, 2);
        String priorityRaw = cell(line, 3);
        String confidenceRaw = cell(line, 4);
        String endType = cell(line, 5);
        String stepsText = cell(line, 6);

        String failReason = null;
        if (title.isEmpty()) {
            failReason = "测试目标（标题）不能为空";
        } else if (title.length() > 300) {
            failReason = "测试目标超过 300 字";
        } else {
            Integer dupRow = seenTitles.putIfAbsent(title, rowNo);
            if (dupRow != null) {
                failReason = "测试目标「" + title + "」与第 " + dupRow + " 行重复";
            }
        }
        Integer priority = 1;
        if (failReason == null && !priorityRaw.isEmpty()) {
            String pv = normalizeNumber(priorityRaw);
            if (!PRIORITIES.contains(pv)) {
                failReason = "优先级仅支持 0/1/2（0=P0 1=P1 2=P2），实际: " + priorityRaw;
            } else {
                priority = Integer.parseInt(pv);
            }
        }
        Integer confidence = 2;
        if (failReason == null && !confidenceRaw.isEmpty()) {
            String cv = normalizeNumber(confidenceRaw);
            if (!CONFIDENCES.contains(cv)) {
                failReason = "置信度仅支持 1/2/3（1=高 2=中 3=低），实际: " + confidenceRaw;
            } else {
                confidence = Integer.parseInt(cv);
            }
        }
        if (failReason == null) {
            String et = endType.isEmpty() ? "web" : endType;
            if (!END_TYPES.contains(et)) {
                failReason = "终端类型仅支持 web/mobile/pos/multi，实际: " + endType;
            }
            endType = et;
        }
        List<CaseStepInput> steps = parseSteps(stepsText, rowNo, failReason);
        return new ImportCaseRow(rowNo, title.isEmpty() ? null : title, precondition, expectedResult,
                priority, confidence, endType, steps, failReason);
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

    
    private List<CaseStepInput> parseSteps(String stepsText, int rowNo, String existingFail) {
        if (stepsText.isEmpty()) {
            return List.of();
        }
        List<CaseStepInput> steps = new ArrayList<>();
        String[] parts = stepsText.split(";");
        int seq = 1;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String desc;
            int isAssert;
            int arrow = p.indexOf("=>");
            if (arrow >= 0) {
                String op = p.substring(0, arrow).trim();
                String exp = p.substring(arrow + 2).trim();
                desc = exp.isEmpty() ? op : op + " => " + exp;
                isAssert = exp.isEmpty() ? 0 : 1;
            } else {
                desc = p;
                isAssert = 0;
            }
            if (desc.length() > 500) {
                desc = desc.substring(0, 500);
            }
            steps.add(new CaseStepInput(seq++, desc, isAssert));
        }
        return steps;
    }

    private String cell(Map<Integer, String> line, int index) {
        String v = line.get(index);
        return v == null ? "" : v.trim();
    }
}
