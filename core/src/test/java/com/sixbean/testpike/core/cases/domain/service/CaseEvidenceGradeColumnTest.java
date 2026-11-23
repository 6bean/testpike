package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-grade column contract on tp_test_case (migration V552, live DB read-back).
 * <p>Why this gate exists: off-agent script write-back grades how much proof a
 * script carries (0 none / 1 off-platform self-report / 2 evidence package on
 * file / 3 platform re-validation passed). The grade is only trustworthy if the
 * storage contract holds before any service starts writing it, so this test
 * reads the live slot database (no mock) and asserts column shape, the value
 * domain CHECK, and that pre-existing rows were never backfilled retroactively.
 * <p>Bidirectional fixture (anti no-op detection): a legal grade lands, an
 * illegal grade is rejected by the database itself. Both run inside a rolled
 * back transaction, so factory rows keep grade 0 (R37: no TRUNCATE, no residue).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseEvidenceGradeColumnTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void columnExistsWithSmallintDefaultZeroNotNull() {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "select data_type, column_default, is_nullable "
                        + "from information_schema.columns "
                        + "where table_name = 'tp_test_case' and column_name = 'evidence_grade'");
        assertEquals("smallint", col.get("data_type"));
        assertEquals("NO", col.get("is_nullable"));
        assertNotNull(col.get("column_default"));
        assertTrue(((String) col.get("column_default")).contains("0"),
                "factory default must be 0, got " + col.get("column_default"));
    }

    @Test
    void valueDomainCheckCoversExactlyFourGrades() {
        String def = jdbcTemplate.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conname = 'ck_test_case_evidence_grade'",
                String.class);
        assertNotNull(def, "named CHECK must exist on tp_test_case");
        for (String grade : List.of("0", "1", "2", "3")) {
            assertTrue(def.contains(grade), "constraint must allow grade " + grade + ", got " + def);
        }
    }

    @Test
    void preExistingRowsWereNeverBackfilledRetroactively() {
        Integer drifted = jdbcTemplate.queryForObject(
                "select count(*) from tp_test_case where evidence_grade <> 0", Integer.class);
        assertEquals(0, drifted, "historical rows keep grade 0; no backfill UPDATE allowed");
    }

    @Test
    @Transactional
    void legalGradeLandsAndIllegalGradeIsRejectedByTheDatabase() {
        Long caseId = jdbcTemplate.queryForObject(
                "select min(id) from tp_test_case", Long.class);
        assertNotNull(caseId, "factory baseline must carry at least one case row");
        int touched = jdbcTemplate.update(
                "update tp_test_case set evidence_grade = 2 where id = ?", caseId);
        assertEquals(1, touched, "legal grade 2 (evidence package on file) must be accepted");
        // The illegal write aborts this rolled-back test transaction on purpose;
        // nothing after it may depend on the session staying usable.
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "update tp_test_case set evidence_grade = 4 where id = ?", caseId));
    }
}
