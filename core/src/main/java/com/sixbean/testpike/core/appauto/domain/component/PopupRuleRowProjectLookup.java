package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowOwnershipReads;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Popup-rule bridge from a governed read row to the project that owns it.
 *
 * <p>Popup rules are learned per project and carry the owning project as their isolation column, so
 * the bridge reads exactly that column. A rule always hangs under a project: an unresolvable row,
 * or a row whose project column is empty, fails closed.
 */
@Component
public class PopupRuleRowProjectLookup implements RowProjectLookup {

    private static final String TABLE = "tp_appauto_popup_rule";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PopupRuleRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "popupRule";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long ruleId = RowIdValues.longOrNull(rowIdValue);
        if (ruleId == null) {
            return null;
        }
        return RowOwnershipReads.gatedBy(RowOwnershipReads.owningProjectOfIsolationColumnRow(
                jdbcTemplate, TABLE, ruleId));
    }
}
