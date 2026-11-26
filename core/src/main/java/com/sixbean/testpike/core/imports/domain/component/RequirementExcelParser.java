package com.sixbean.testpike.core.imports.domain.component;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.imports.api.dto.ImportRequirementRow;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class RequirementExcelParser {

    
    public static final List<String> HEADERS =
            List.of("需求标题", "章节", "验收标准", "来源");

    public List<ImportRequirementRow> parse(byte[] file, String fileName) {
        String name = fileName == null || fileName.isBlank() ? "未命名文件" : fileName;
        if (file == null || file.length == 0) {
            throw new ServiceException("上传文件「" + name + "」为空、未读到内容——请在「存量导入」弹窗点「下载模板」，按模板列填写后重新上传 .xlsx 文件", BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<Integer, String>> data;
        try {
            data = EasyExcel.read(new ByteArrayInputStream(file)).headRowNumber(1).sheet().doReadSync();
        } catch (Exception e) {
            throw new ServiceException("上传文件「" + name + "」不是可解析的 .xlsx 格式——请在「存量导入」弹窗点「下载模板」另存后按列填写，勿直接改扩展名", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Integer> seenTitles = new HashMap<>();
        List<ImportRequirementRow> rows = new ArrayList<>();
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
            throw new ServiceException("上传文件「" + name + "」没有数据行（至少 1 条；模板列：需求标题/章节/验收标准/来源）——请在「存量导入」弹窗点「下载模板」对照列名填写后重新上传", BizErrorCode.VALIDATION_ERROR);
        }
        return rows;
    }

    private ImportRequirementRow validate(int rowNo, Map<Integer, String> line, Map<String, Integer> seenTitles) {
        String title = cell(line, 0);
        String chapter = cell(line, 1);
        String acceptance = cell(line, 2);
        String sourceRaw = cell(line, 3);

        String failReason = null;
        if (title.isEmpty()) {
            failReason = "需求标题不能为空";
        } else if (title.length() > 300) {
            failReason = "需求标题超过 300 字";
        } else {
            Integer dupRow = seenTitles.putIfAbsent(title, rowNo);
            if (dupRow != null) {
                failReason = "需求标题「" + title + "」与第 " + dupRow + " 行重复";
            }
        }
        Integer source = 3;
        if (failReason == null && !sourceRaw.isEmpty()) {
            String v = sourceRaw.endsWith(".0") ? sourceRaw.substring(0, sourceRaw.length() - 2) : sourceRaw;
            if (!v.equals("1") && !v.equals("2") && !v.equals("3")) {
                failReason = "来源仅支持 1=Jira 2=文档提取 3=手工，实际: " + sourceRaw;
            } else {
                source = Integer.parseInt(v);
            }
        }
        return new ImportRequirementRow(rowNo, title.isEmpty() ? null : title, chapter, acceptance, source, failReason);
    }

    private String cell(Map<Integer, String> line, int index) {
        String v = line.get(index);
        return v == null ? "" : v.trim();
    }
}
