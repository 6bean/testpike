package com.sixbean.testpike.core.tags.domain.component;

import java.util.List;


public final class TagQuerySupport {

    private TagQuerySupport() {
    }

    
    public static void appendCaseTagFilter(StringBuilder sql, List<Object> args,
                                           long projectId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < tagIds.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        sql.append(" AND id IN (SELECT ct.case_id FROM tp_case_tag ct")
                .append(" JOIN tp_tag_dict td ON ct.tag_id = td.id")
                .append(" WHERE td.project_id = ? AND ct.tag_id IN (")
                .append(placeholders).append("))");
        args.add(projectId);
        args.addAll(tagIds);
    }
}
