package com.sixbean.testpike.core.favorite.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.favorite.api.dto.FavoriteItem;
import com.sixbean.testpike.core.favorite.domain.component.FavoriteEntitySpec;
import com.sixbean.testpike.core.favorite.domain.service.FavoriteService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


@Service
public class FavoriteServiceImpl implements FavoriteService {

 
    static final int MAX_FAVORITES = 500;

 
    private static final Set<String> ENTITY_TYPES = Set.of("case", "requirement", "defect", "project");

    
    private static final FavoriteEntitySpec CASE =
            new FavoriteEntitySpec("case", "tp_test_case", "title", "case_no", " AND e.deleted = 0");
    private static final FavoriteEntitySpec REQUIREMENT =
            new FavoriteEntitySpec("requirement", "tp_requirement", "title", "source_id", "");
    private static final FavoriteEntitySpec DEFECT =
            new FavoriteEntitySpec("defect", "tp_defect", "title", "external_key", "");
    private static final FavoriteEntitySpec PROJECT =
            new FavoriteEntitySpec("project", "tp_project", "name", "code", "");

    private final JdbcTemplate jdbcTemplate;

    public FavoriteServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void favorite(long userId, String entityType, long entityId) {
        validateType(entityType);
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_favorite WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
                Long.class, userId, entityType, entityId);
        if (existing != null && existing > 0) {
            return;
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_favorite WHERE user_id = ?", Long.class, userId);
        if (total != null && total >= MAX_FAVORITES) {
            throw new ServiceException("收藏数量已达上限（" + MAX_FAVORITES + "），请清理后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            jdbcTemplate.update("INSERT INTO tp_favorite (user_id, entity_type, entity_id) VALUES (?, ?, ?)",
                    userId, entityType, entityId);
        } catch (DuplicateKeyException e) {
        }
    }

    @Override
    public int unfavorite(long userId, String entityType, long entityId) {
        validateType(entityType);
        return jdbcTemplate.update(
                "DELETE FROM tp_favorite WHERE user_id = ? AND entity_type = ? AND entity_id = ?",
                userId, entityType, entityId);
    }

    @Override
    public List<FavoriteItem> listFavorites(long userId, boolean allVisible, String entityType) {
        if (entityType != null && !entityType.isEmpty()) {
            validateType(entityType);
            return queryByType(userId, allVisible, spec(entityType));
        }
        List<String> branches = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String type : List.of("case", "requirement", "defect", "project")) {
            FavoriteEntitySpec spec = spec(type);
            StringBuilder sql = new StringBuilder(selectHead(spec)).append(selectFrom(userId, allVisible, spec));
            branches.add(sql.toString());
            args.add(userId);
            if (!allVisible) {
                args.add(userId);
            }
        }
        String full = String.join(" UNION ALL ", branches) + " ORDER BY fav_id DESC";
        return jdbcTemplate.query(full, favoriteRowMapper(), args.toArray());
    }

    
    private String selectHead(FavoriteEntitySpec spec) {
        return "SELECT f.id AS fav_id, f.entity_id AS entity_id_fav, '" + spec.type() + "' AS fav_type, e."
                + spec.titleColumn() + " AS fav_title, e." + spec.subtitleColumn() + " AS fav_subtitle"
                + ", to_char(f.created_at, 'YYYY-MM-DD HH24:MI:SS') AS favorited_at";
    }

    
    private String selectFrom(long userId, boolean allVisible, FavoriteEntitySpec spec) {
        boolean projectTable = "tp_project".equals(spec.table());
        String projectScopeExpr = projectTable ? "e.id" : "e.project_id";
        StringBuilder sql = new StringBuilder(" FROM tp_favorite f JOIN ").append(spec.table()).append(" e")
                .append(" ON e.id = f.entity_id WHERE f.user_id = ?")
                .append(spec.deletedFilter());
        if (!allVisible) {
            sql.append(" AND EXISTS (SELECT 1 FROM tp_user_project up WHERE up.project_id = ")
                    .append(projectScopeExpr).append(" AND up.user_id = ?)");
        }
        return sql.toString();
    }

    
    private org.springframework.jdbc.core.RowMapper<FavoriteItem> favoriteRowMapper() {
        return (rs, i) -> new FavoriteItem(rs.getString("fav_type"), rs.getLong("entity_id_fav"),
                rs.getString("fav_title"), rs.getString("fav_subtitle"), rs.getString("favorited_at"));
    }

    
    private List<FavoriteItem> queryByType(long userId, boolean allVisible, FavoriteEntitySpec spec) {
        String sql = selectHead(spec) + selectFrom(userId, allVisible, spec)
                + " ORDER BY f.id DESC";
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (!allVisible) {
            args.add(userId);
        }
        return jdbcTemplate.query(sql, favoriteRowMapper(), args.toArray());
    }

    private void validateType(String entityType) {
        if (entityType == null || !ENTITY_TYPES.contains(entityType)) {
            throw new ServiceException("收藏类型不支持（仅支持：用例/需求点/缺陷/项目）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private FavoriteEntitySpec spec(String entityType) {
        return switch (entityType) {
            case "case" -> CASE;
            case "requirement" -> REQUIREMENT;
            case "defect" -> DEFECT;
            case "project" -> PROJECT;
            default -> throw new ServiceException("收藏类型不支持（仅支持：用例/需求点/缺陷/项目）",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }
}
