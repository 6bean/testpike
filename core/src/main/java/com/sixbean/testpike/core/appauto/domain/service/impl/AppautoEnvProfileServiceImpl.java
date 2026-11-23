package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoCatalogCodec;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoEnvProfile;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.component.LoginRecipePayloadContract;
import com.sixbean.testpike.core.asset.domain.component.SharedAssetVisibilityGuard;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>Login-recipe binding (W5 phase 2, segment B1b): {@code loginRecipeAssetId} is validated
 * and resolved here — the single source both the save-side gate and the dispatch envelope
 * call, so no second recipe-reading implementation can drift next to it.
 */
@Service
public class AppautoEnvProfileServiceImpl implements AppautoEnvProfileService {

 
    static final Set<String> PROFILE_KINDS =
            Set.of("MP_DEVTOOLS", "ANDROID_SDK", "IOS_SIMULATOR", "HARMONY_HDC");

    /** The shared-asset kind an env profile may bind as its login recipe. */
    private static final String KIND_LOGIN_RECIPE = "login_recipe";

    private static final String SELECT_COLUMNS = """
            id, project_id, name, profile_kind, payload::text AS payload_json, remark,
            login_recipe_asset_id, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoCatalogCodec codec;
    private final SharedAssetService sharedAssetService;

    public AppautoEnvProfileServiceImpl(NamedParameterJdbcTemplate jdbc, AppautoCatalogCodec codec,
                                        SharedAssetService sharedAssetService) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.sharedAssetService = sharedAssetService;
    }

    @Override
    @Audit(objectType = "appauto_env_profile", action = "create", objectId = "#result")
    public long create(long projectId, AppautoEnvProfileInput input) {
        validate(input);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("name", input.name().trim())
                .addValue("kind", input.profileKind().trim().toUpperCase())
                .addValue("payload", codec.writeJson(normalizePayload(input.payload())))
                .addValue("remark", trimToNull(input.remark()))
                .addValue("recipe", input.loginRecipeAssetId());
        try {
            Long inserted = jdbc.queryForObject("INSERT INTO tp_appauto_env_profile "
                            + "(project_id, name, profile_kind, payload, remark, login_recipe_asset_id) "
                            + "VALUES (:pid, :name, :kind, CAST(:payload AS jsonb), :remark, :recipe) "
                            + "RETURNING id",
                    params, Long.class);
            return inserted == null ? 0L : inserted;
        } catch (DuplicateKeyException e) {
            throw conflict(input.name().trim());
        }
    }

    @Override
    @Audit(objectType = "appauto_env_profile", action = "update", objectId = "#id")
    public void update(long id, AppautoEnvProfileInput input) {
        findOr404(id);
        validate(input);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", input.name().trim())
                .addValue("kind", input.profileKind().trim().toUpperCase())
                .addValue("payload", codec.writeJson(normalizePayload(input.payload())))
                .addValue("remark", trimToNull(input.remark()))
                .addValue("recipe", input.loginRecipeAssetId());
        try {
            jdbc.update("UPDATE tp_appauto_env_profile SET name = :name, profile_kind = :kind, "
                    + "payload = CAST(:payload AS jsonb), remark = :remark, "
                    + "login_recipe_asset_id = :recipe, updated_at = now() "
                    + "WHERE id = :id", params);
        } catch (DuplicateKeyException e) {
            throw conflict(input.name().trim());
        }
    }

    @Override
    public AppautoEnvProfileInfo get(long id) {
        return toInfo(findOr404(id));
    }

    @Override
    public List<AppautoEnvProfileInfo> list(long projectId, String profileKind) {
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId);
        String where = "project_id = :pid";
        if (profileKind != null && !profileKind.isBlank()) {
            where += " AND profile_kind = :kind";
            params.addValue("kind", profileKind.trim().toUpperCase());
        }
        return findRows(where, params).stream().map(this::toInfo).toList();
    }

    @Override
    @Audit(objectType = "appauto_env_profile", action = "delete", objectId = "#id")
    public void delete(long id) {
        findOr404(id);
        jdbc.update("DELETE FROM tp_appauto_env_profile WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    /**
     * Resolve the CURRENT head of the bound recipe chain and hand back its app.* steps.
     * <p>Head resolution goes through the asset chain's SQL single source
     * ({@link SharedAssetService#resolveHeadId}), so re-publishing a recipe takes effect
     * on the next dispatch without touching any profile row. Every refusal is a plain
     * human message naming what to fix; the steps keep their credential placeholders —
     * vault resolution stays at the dispatch injection point.
     */
    @Override
    public List<Map<String, Object>> resolveLoginRecipeSteps(long recipeAssetId) {
        SharedAssetInfo bound = sharedAssetService.get(recipeAssetId);
        if (!KIND_LOGIN_RECIPE.equals(bound.kind())) {
            throw new ServiceException("环境档案的登录配方引用指向了「" + bound.kind()
                    + "」类型的共享资产——只能引用 login_recipe（登录配方）类型，"
                    + "请到「共享资产池」选择「登录配方」后，在「项目环境」重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        long headId = sharedAssetService.resolveHeadId(bound.projectId(),
                KIND_LOGIN_RECIPE, bound.name());
        SharedAssetInfo head = sharedAssetService.get(headId);
        if (head.status() != SharedAssetVisibilityGuard.STATUS_PUBLISHED) {
            throw new ServiceException("登录配方「" + head.name() + "」已下架，不能作为环境档案的登录态配方"
                    + "——请到「共享资产池」重新发布，或换一份启用中的配方", BizErrorCode.VALIDATION_ERROR);
        }
        return LoginRecipePayloadContract.appStepsOf(head.payloadJson());
    }


    private void validate(AppautoEnvProfileInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("环境档案名称必填（项目内唯一，用例与建单按名引用）"
                    + "——请在「项目环境」新建表单填写档案名称",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String kind = input.profileKind() == null ? "" : input.profileKind().trim().toUpperCase();
        if (!PROFILE_KINDS.contains(kind)) {
            throw new ServiceException("档案型仅支持 " + String.join("/", PROFILE_KINDS.stream().sorted().toList())
                    + "，收到「" + input.profileKind() + "」——请在「项目环境」表单的档案型下拉重新选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // A bound recipe is checked at save (fail-closed with a pointer), so a profile
        // can never be stored in a state that dispatch would later refuse silently.
        if (input.loginRecipeAssetId() != null) {
            resolveLoginRecipeSteps(input.loginRecipeAssetId());
        }
    }

    
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        return payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    private ServiceException conflict(String name) {
        return new ServiceException("本项目已存在同名环境档案「" + name
                + "」，请到「项目环境」换个名字新建，或直接编辑既有档案", BizErrorCode.RESOURCE_CONFLICT);
    }

    private AppautoEnvProfile findOr404(long id) {
        List<AppautoEnvProfile> rows = findRows("id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("环境档案不存在（可能已删除）——请刷新「项目环境」列表确认档案",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private List<AppautoEnvProfile> findRows(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_appauto_env_profile WHERE " + where,
                params, new BeanPropertyRowMapper<>(AppautoEnvProfile.class));
    }

    private AppautoEnvProfileInfo toInfo(AppautoEnvProfile p) {
        return new AppautoEnvProfileInfo(p.getId(), p.getProjectId(), p.getName(),
                p.getProfileKind(), codec.readObjectMap(p.getPayloadJson()), p.getRemark(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getLoginRecipeAssetId());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
