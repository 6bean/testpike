package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewVerdict;
import com.sixbean.testpike.core.session.domain.component.LoginExemptReviewSupport;
import com.sixbean.testpike.core.session.domain.component.LoginExemptSupport;
import com.sixbean.testpike.core.session.domain.service.LoginExemptService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Login exemption ledger implementation (NamedParameterJdbcTemplate base
 * path, same idiom as the rest of the session domain).
 *
 * <p>Storage decision: the declaration is one jsonb slot on the environment
 * profile row it describes (the AV design doc freezes it as a structured
 * LoginExemptSupport — this class owns no rules of its own (red line 6).
 * Re-declaring overwrites the slot in place; a NULL slot reads as the
 * honest undeclared view, so legacy environment rows keep working unchanged.
 *
 * <p>Vault cross-check on declare: a master_code claim only holds when the
 * referenced cred_key is registered in the SAME project vault — exemption
 * declarations are project assets and cannot borrow another project's key
 * (isolation red line, same semantics as {{CRED:}} resolution).
 */
@Service
public class LoginExemptServiceImpl implements LoginExemptService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CredentialService credentialService;
    private final LoginExemptSupport support;
    private final LoginExemptReviewSupport reviewSupport;

    public LoginExemptServiceImpl(NamedParameterJdbcTemplate jdbc,
                                  CredentialService credentialService,
                                  LoginExemptSupport support,
                                  LoginExemptReviewSupport reviewSupport) {
        this.jdbc = jdbc;
        this.credentialService = credentialService;
        this.support = support;
        this.reviewSupport = reviewSupport;
    }

    @Override
    @Transactional
    public LoginExemptInfo declare(long envId, LoginExemptInput input) {
        long projectId = envProjectId(envId);
        String storage = support.toStorageJson(input);
        if (LoginExemptSupport.KIND_MASTER_CODE.equals(input.kind().trim())
                && !keyRegisteredInProject(projectId, input.credKey().trim())) {
            throw new ServiceException(
                    "凭据键「" + input.credKey().trim() + "」在本项目尚未建档——"
                            + "请先到「凭据保险箱」页登记该键名的凭据，再回到「登录豁免声明」弹窗的"
                            + "「万能验证码凭据键」重新引用后保存。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        jdbc.update("UPDATE tp_project_env SET login_exempt_json = CAST(:j AS jsonb),"
                        + " updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", envId).addValue("j", storage));
        return find(envId);
    }

    @Override
    @Transactional
    public LoginExemptInfo recordReview(long envId, LoginExemptReviewSignal signal) {
        LoginExemptInfo current = find(envId);
        if (!current.declared()) {
            throw new ServiceException(
                    "环境档案 #" + envId + " 尚未登记登录豁免声明——请先在项目详情「环境配置」该行点「登录豁免」登记声明，再提交复核。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        LoginExemptReviewVerdict verdict = reviewSupport.decide(current.kind(), signal);
        String storage = support.withReview(current, verdict.status(), verdict.detail(),
                Instant.now().toString());
        jdbc.update("UPDATE tp_project_env SET login_exempt_json = CAST(:j AS jsonb),"
                        + " updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", envId).addValue("j", storage));
        return find(envId);
    }

    @Override
    public LoginExemptInfo find(long envId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id, login_exempt_json::text AS storage"
                        + " FROM tp_project_env WHERE id = :id",
                new MapSqlParameterSource("id", envId));
        if (rows.isEmpty()) {
            throw envNotFound(envId);
        }
        Map<String, Object> row = rows.get(0);
        return support.viewOf(((Number) row.get("id")).longValue(),
                ((Number) row.get("project_id")).longValue(),
                (String) row.get("storage"));
    }

    private long envProjectId(long envId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT project_id FROM tp_project_env WHERE id = :id",
                new MapSqlParameterSource("id", envId));
        if (rows.isEmpty()) {
            throw envNotFound(envId);
        }
        return ((Number) rows.get(0).get("project_id")).longValue();
    }

    private boolean keyRegisteredInProject(long projectId, String credKey) {
        return credentialService.list(projectId, null).stream()
                .anyMatch(info -> credKey.equals(info.credKey()));
    }

    /**
     * One wording for both lookups of this class: names the row that is gone
     * and the on-screen door that can recreate it (R63 three elements).
     */
    private static ServiceException envNotFound(long envId) {
        return new ServiceException(
                "环境档案 #" + envId + " 不存在——请到项目详情「环境配置」列表核对该行是否已被删除；"
                        + "如需继续登记登录豁免，请先点「新增环境」重新建档。",
                BizErrorCode.NOT_FOUND);
    }
}
