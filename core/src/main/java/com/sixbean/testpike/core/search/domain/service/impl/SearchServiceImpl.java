package com.sixbean.testpike.core.search.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.LikePattern;
import com.sixbean.testpike.core.search.api.dto.SearchItem;
import com.sixbean.testpike.core.search.api.dto.SearchResults;
import com.sixbean.testpike.core.search.domain.service.SearchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class SearchServiceImpl implements SearchService {

 
    private static final int CATEGORY_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;

    public SearchServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SearchResults search(long userId, boolean allVisible, String keyword) {
        String like = LikePattern.toLikeParam(keyword);
        List<SearchItem> cases = searchTable(userId, allVisible, like,
                "tp_test_case", "case", "case_no", "title",
                "case_no ILIKE ? ESCAPE '\\' OR title ILIKE ? ESCAPE '\\'", 2);
        List<SearchItem> requirements = searchTable(userId, allVisible, like,
                "tp_requirement", "requirement", "source_id", "title",
                "title ILIKE ? ESCAPE '\\' OR source_id ILIKE ? ESCAPE '\\'", 2);
        List<SearchItem> defects = searchTable(userId, allVisible, like,
                "tp_defect", "defect", "external_key", "title",
                "title ILIKE ? ESCAPE '\\'", 1);
        List<SearchItem> projects = searchProjects(userId, allVisible, like);
        return new SearchResults(cases, requirements, defects, projects);
    }

    
    private List<SearchItem> searchTable(long userId, boolean allVisible, String like,
                                         String table, String entityType,
                                         String subtitleColumn, String titleColumn,
                                         String matchCondition, int matchPlaceholders) {
        StringBuilder sql = new StringBuilder("SELECT id, project_id, ")
                .append(titleColumn).append(", ").append(subtitleColumn)
                .append(" FROM ").append(table).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!allVisible) {
            sql.append(" AND EXISTS (SELECT 1 FROM tp_user_project up"
                    + " WHERE up.project_id = ").append(table).append(".project_id AND up.user_id = ?)");
            args.add(userId);
        }
        if (like != null) {
            sql.append(" AND (").append(matchCondition).append(")");
            for (int i = 0; i < matchPlaceholders; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY EXISTS (SELECT 1 FROM tp_favorite fav"
                + " WHERE fav.user_id = ? AND fav.entity_type = ? AND fav.entity_id = ")
                .append(table).append(".id) DESC, id DESC LIMIT ").append(CATEGORY_LIMIT);
        args.add(userId);
        args.add(entityType);
        return jdbcTemplate.query(sql.toString(),
                (rs, i) -> new SearchItem(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString(titleColumn), rs.getString(subtitleColumn)),
                args.toArray());
    }

    
    private List<SearchItem> searchProjects(long userId, boolean allVisible, String like) {
        StringBuilder sql = new StringBuilder("SELECT id, name, code FROM tp_project p WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!allVisible) {
            sql.append(" AND EXISTS (SELECT 1 FROM tp_user_project up"
                    + " WHERE up.project_id = p.id AND up.user_id = ?)");
            args.add(userId);
        }
        if (like != null) {
            sql.append(" AND (p.name ILIKE ? ESCAPE '\\' OR p.code ILIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY EXISTS (SELECT 1 FROM tp_favorite fav"
                + " WHERE fav.user_id = ? AND fav.entity_type = 'project' AND fav.entity_id = p.id) DESC,"
                + " p.id DESC LIMIT ").append(CATEGORY_LIMIT);
        args.add(userId);
        return jdbcTemplate.query(sql.toString(),
                (rs, i) -> new SearchItem(rs.getLong("id"), rs.getLong("id"),
                        rs.getString("name"), rs.getString("code")),
                args.toArray());
    }
}
