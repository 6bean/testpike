package com.sixbean.testpike.core.imports.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.component.ExcelTemplateBuilder;
import com.sixbean.testpike.core.imports.api.dto.ImportCaseRow;
import com.sixbean.testpike.core.imports.api.dto.ImportItemResult;
import com.sixbean.testpike.core.imports.api.dto.ImportRequirementRow;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.imports.domain.component.CaseExcelParser;
import com.sixbean.testpike.core.imports.domain.component.RequirementExcelParser;
import com.sixbean.testpike.core.imports.domain.service.ImportService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Service
public class ImportServiceImpl implements ImportService {

    private static final int TARGET_STATUS_PENDING_REVIEW = 1;
    private static final int TARGET_STATUS_APPROVED = 3;

    private final CaseExcelParser caseExcelParser;
    private final RequirementExcelParser requirementExcelParser;
    private final CaseService caseService;
    private final RequirementService requirementService;
    private final ProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    public ImportServiceImpl(CaseExcelParser caseExcelParser, RequirementExcelParser requirementExcelParser,
                             CaseService caseService, RequirementService requirementService,
                             ProjectService projectService, JdbcTemplate jdbcTemplate) {
        this.caseExcelParser = caseExcelParser;
        this.requirementExcelParser = requirementExcelParser;
        this.caseService = caseService;
        this.requirementService = requirementService;
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Audit(objectType = "case_import", action = "import", objectId = "#projectId", after = "#result")
    public ImportResult importCases(Long operatorId, long projectId, byte[] file, String fileName,
                                    Integer targetStatus) {
        projectService.get(projectId);
        int status = normalizeTargetStatus(targetStatus);
        List<ImportCaseRow> rows = caseExcelParser.parse(file, fileName);
        Set<String> existingTitles = loadCaseTitles(projectId);

        List<ImportItemResult> items = new ArrayList<>();
        int success = 0;
        int fail = 0;
        for (ImportCaseRow row : rows) {
            if (row.failReason() != null) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, row.failReason(), null));
                continue;
            }
            if (existingTitles.contains(row.title())) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false,
                        "用例「" + row.title() + "」已存在（重复），未导入（重试成功项不重复导入）", null));
                continue;
            }
            CaseCreateInput input = new CaseCreateInput(projectId, row.title(), row.precondition(),
                    row.expectedResult(), row.priority(), row.confidence(), row.endType(), status, null, 3, "存量导入", row.steps());
            try {
                CaseInfo created = caseService.create(operatorId, input);
                success++;
                existingTitles.add(row.title());
                items.add(new ImportItemResult(row.rowNumber(), true, null, created.id()));
            } catch (ServiceException e) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, e.getMessage(), null));
            }
        }
        return new ImportResult(success, fail, items);
    }

    
    @Override
    @Audit(objectType = "requirement_import", action = "import", objectId = "#projectId", after = "#result")
    public ImportResult importRequirements(Long operatorId, long projectId, byte[] file, String fileName) {
        projectService.get(projectId);
        List<ImportRequirementRow> rows = requirementExcelParser.parse(file, fileName);
        Set<String> existingTitles = loadRequirementTitles(projectId);

        List<ImportItemResult> items = new ArrayList<>();
        int success = 0;
        int fail = 0;
        for (ImportRequirementRow row : rows) {
            if (row.failReason() != null) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, row.failReason(), null));
                continue;
            }
            if (existingTitles.contains(row.title())) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false,
                        "需求点「" + row.title() + "」已存在（重复），未导入", null));
                continue;
            }
            RequirementCreateInput input = new RequirementCreateInput(projectId, row.source(), null,
                    row.title(), row.acceptanceCriteria(), row.chapter());
            try {
                var created = requirementService.create(operatorId, input);
                success++;
                existingTitles.add(row.title());
                items.add(new ImportItemResult(row.rowNumber(), true, null, created.id()));
            } catch (ServiceException e) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, e.getMessage(), null));
            }
        }
        return new ImportResult(success, fail, items);
    }

    @Override
    @Audit(objectType = "case_import", action = "import_retry", objectId = "#projectId", after = "#result")
    public ImportResult retryFailedCases(Long operatorId, long projectId, byte[] file, String fileName,
                                         Integer targetStatus, List<Integer> retryRows) {
        if (retryRows == null || retryRows.isEmpty()) {
            throw new ServiceException("重试请求未包含失败行号（行号不能为空）——请在「存量导入」结果的「失败明细」勾选需重试的行，再点「一键重试失败项」", BizErrorCode.VALIDATION_ERROR);
        }
        projectService.get(projectId);
        int status = normalizeTargetStatus(targetStatus);
        List<ImportCaseRow> rows = caseExcelParser.parse(file, fileName);
        Set<Integer> retrySet = new HashSet<>(retryRows);
        Set<String> existingTitles = loadCaseTitles(projectId);

        List<ImportItemResult> items = new ArrayList<>();
        int success = 0;
        int fail = 0;
        for (ImportCaseRow row : rows) {
            if (!retrySet.contains(row.rowNumber())) {
                continue;
            }
            if (row.failReason() != null) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, row.failReason(), null));
                continue;
            }
            if (existingTitles.contains(row.title())) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false,
                        "用例「" + row.title() + "」已存在（重复），未导入（成功项不重复导入）", null));
                continue;
            }
            CaseCreateInput input = new CaseCreateInput(projectId, row.title(), row.precondition(),
                    row.expectedResult(), row.priority(), row.confidence(), row.endType(), status, null, 3, "存量导入", row.steps());
            try {
                CaseInfo created = caseService.create(operatorId, input);
                success++;
                existingTitles.add(row.title());
                items.add(new ImportItemResult(row.rowNumber(), true, null, created.id()));
            } catch (ServiceException e) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, e.getMessage(), null));
            }
        }
        return new ImportResult(success, fail, items);
    }

    
    private int normalizeTargetStatus(Integer targetStatus) {
        int status = targetStatus == null ? TARGET_STATUS_PENDING_REVIEW : targetStatus;
        if (status != TARGET_STATUS_PENDING_REVIEW && status != TARGET_STATUS_APPROVED) {
            throw new ServiceException("导入后状态仅支持「待评审」(1) 或「已通过」(3)，实际传入: " + status + "——请在「存量导入」弹窗「导入后状态」下拉重新选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return status;
    }

    private Set<String> loadCaseTitles(long projectId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT title FROM tp_test_case WHERE project_id = ? AND deleted = 0", String.class, projectId));
    }

    private Set<String> loadRequirementTitles(long projectId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT title FROM tp_requirement WHERE project_id = ?", String.class, projectId));
    }

    @Override
    public byte[] caseTemplate() {
        return ExcelTemplateBuilder.build(CaseExcelParser.HEADERS, "用例导入模板");
    }

    @Override
    public byte[] requirementTemplate() {
        return ExcelTemplateBuilder.build(RequirementExcelParser.HEADERS, "需求导入模板");
    }
}
