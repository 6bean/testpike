package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row-level ownership on the read door.
 *
 * <p>The governance overlay used to be able to say only "this endpoint family is verified safe to
 * expose". For entries addressed by a single data row ({@code GET .../cases/{caseId}}) that verdict
 * was too coarse: once a family was verified, every project's rows opened to every logged-in user.
 * A row may now additionally name the entity and the id parameter that locate the concrete record;
 * this component resolves that row's owning project and applies the very same membership decision
 * the door already applies to project-anchored calls (one rule, one place).
 *
 * <p>Fail-closed on every unresolved step: no id argument, an entity whose family bridge has not
 * landed yet, or a row id matching nothing all refuse the call. Rows without a row anchor keep the
 * previous family-level verdict untouched, so the 130+ already-registered entries do not start
 * failing because of this change. A row that belongs to no project — a system-level bucket row
 * such as a shared skill — is served to every identified operator, the same two visibility tiers
 * its own page applies.
 */
@Component
public class ApiAccessRowOwnership {

    private final ProjectRoleResolver projectRoleResolver;
    private final AdminAlertRecipients adminAlertRecipients;
    private final Map<String, RowProjectLookup> lookups;

    public ApiAccessRowOwnership(ProjectRoleResolver projectRoleResolver,
                                 AdminAlertRecipients adminAlertRecipients,
                                 ObjectProvider<RowProjectLookup> rowProjectLookups) {
        this.projectRoleResolver = projectRoleResolver;
        this.adminAlertRecipients = adminAlertRecipients;
        Map<String, RowProjectLookup> byEntity = new LinkedHashMap<>();
        rowProjectLookups.orderedStream().forEach(lookup -> byEntity.put(lookup.entity(), lookup));
        this.lookups = Map.copyOf(byEntity);
    }

    /**
     * The shared project-membership decision: administrators of the alert set pass, everyone else
     * must belong to every project the call names. Both the project-anchored form and the
     * row-anchored form go through this one method so the two can never drift apart.
     */
    public String membershipProblem(List<Long> projectIds, Long operatorId) {
        if (operatorId == null) {
            return "直通只代读操作人自己可见的数据，本次调用没有可判定的操作人";
        }
        if (adminAlertRecipients.adminUserIds().contains(operatorId)) {
            return null;
        }
        for (Long projectId : projectIds) {
            if (!projectRoleResolver.isMember(projectId, operatorId)) {
                return "操作人不属于项目 " + projectId + "，越权读取已被拒绝（本门只代读自己可见的项目）";
            }
        }
        return null;
    }

    /**
     * Row-level verdict for one governed read. Null means no row-level objection.
     *
     * @param row        governance overlay row for the endpoint (null tolerated)
     * @param operatorId the acting person the door itself holds, never a call argument
     * @param arguments  the call's addressable arguments (the row id is read from here)
     */
    public String problem(ApiAccessGovernanceView row, Long operatorId, Map<String, Object> arguments) {
        if (row == null || row.rowOwnerEntity() == null || row.rowOwnerEntity().isBlank()) {
            return null;
        }
        String idParam = row.rowOwnerIdParam();
        if (idParam == null || idParam.isBlank()) {
            return "该条目的行级归属缺少定位参数名（" + ApiAccessGovernanceRepository.TABLE
                    + ".row_owner_id_param 未填），本次代读被拒绝";
        }
        Object rowId = arguments.get(idParam);
        if (rowId == null) {
            return "缺少定位参数 " + idParam + "，无法判定该行数据属于哪个项目，本次代读被拒绝";
        }
        RowProjectLookup lookup = lookups.get(row.rowOwnerEntity());
        if (lookup == null) {
            return "实体 " + row.rowOwnerEntity() + " 的行归属解析尚未接入直通门，该条目暂不开放";
        }
        List<Long> ownerProjects = lookup.ownerProjectIds(rowId);
        if (ownerProjects == null) {
            return "按 " + idParam + "=" + rowId + " 未找到对应数据行，无法判定归属，本次代读被拒绝";
        }
        return membershipProblem(ownerProjects, operatorId);
    }
}
