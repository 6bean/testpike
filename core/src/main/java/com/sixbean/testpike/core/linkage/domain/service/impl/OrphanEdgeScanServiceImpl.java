package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.linkage.api.dto.LinkageOrphanEdge;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;
import com.sixbean.testpike.core.linkage.domain.component.LinkageObjectTables;
import com.sixbean.testpike.core.linkage.domain.service.OrphanEdgeScanService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads the live PostgreSQL catalog (pg_constraint) and the edge ledger,
 * then reports every foreign key onto an armed member's table that no edge
 * row covers .
 * <p>Shape notes:
 * <ul>
 * <li>scope starts where the ledger starts: a member is armed by one
 * enabled non-exempt edge, so registering the first edge for an object
 * type forces that type's whole foreign-key fan into the ledger (or under
 * a written exemption) instead of leaving half-covered members;</li>
 * <li>coverage is decided by table identity, read live on every call: an
 * edge names its downstream as the table itself, the table without its
 * {@code tp_} prefix, or another bound token resolving to the same table —
 * whichever spelling the ledger uses counts, no second naming rule;</li>
 * <li>exemption rows cover their pair (that is their whole purpose),
 * disabled rows cover nothing — the registry keeps them for audit only.</li>
 * </ul>
 */
@Service
public class OrphanEdgeScanServiceImpl implements OrphanEdgeScanService {

    /** All foreign keys in the current schema, one row per key column. */
    private static final String FK_CENSUS_SQL = """
            SELECT con.conname AS fk_name, src.relname AS referencing_table,
                   tgt.relname AS referenced_table, att.attname AS fk_column
            FROM pg_constraint con
            JOIN pg_class src ON src.oid = con.conrelid
            JOIN pg_class tgt ON tgt.oid = con.confrelid
            CROSS JOIN LATERAL unnest(con.conkey) AS k(attnum)
            JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
            WHERE con.contype = 'f'
            """;

    /** Edge rows the scan reads: enabled only, exempt kept (they cover pairs). */
    private static final String EDGE_CENSUS_SQL = """
            SELECT edge_key, upstream_object_type, downstream_object_type, exempt
            FROM tp_change_linkage
            WHERE enabled = 1
            """;

    private final NamedParameterJdbcTemplate named;

    public OrphanEdgeScanServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public OrphanEdgeScanReport scan() {
        List<Map<String, Object>> edges =
                named.queryForList(EDGE_CENSUS_SQL, new MapSqlParameterSource());
        Set<String> armed = edges.stream()
                .filter(OrphanEdgeScanServiceImpl::isNonExempt)
                .map(OrphanEdgeScanServiceImpl::upstream)
                .filter(LinkageObjectTables::isBound)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> busTokens = Arrays.stream(ObjectChangedEventType.values())
                .map(ObjectChangedEventType::objectType)
                .collect(Collectors.toCollection(HashSet::new));

        List<LinkageOrphanEdge> orphans = new ArrayList<>();
        int covered = 0;
        for (Map<String, Object> fk : named.queryForList(
                FK_CENSUS_SQL, new MapSqlParameterSource())) {
            Optional<String> member =
                    LinkageObjectTables.tokenForTable((String) fk.get("referenced_table"));
            if (member.isEmpty() || !armed.contains(member.get())) {
                continue;
            }
            String referencingTable = (String) fk.get("referencing_table");
            boolean isCovered = edges.stream()
                    .anyMatch(edge -> upstream(edge).equals(member.get())
                            && covers(edge, referencingTable));
            if (isCovered) {
                covered++;
            } else {
                orphans.add(new LinkageOrphanEdge(member.get(), referencingTable,
                        (String) fk.get("referenced_table"), (String) fk.get("fk_name"),
                        (String) fk.get("fk_column")));
            }
        }
        orphans.sort(Comparator.comparing(LinkageOrphanEdge::referencingTable)
                .thenComparing(LinkageOrphanEdge::fkName));

        List<String> deadEdges = edges.stream()
                .filter(edge -> !busTokens.contains(upstream(edge)))
                .map(OrphanEdgeScanServiceImpl::edgeKey)
                .sorted()
                .collect(Collectors.toList());

        Set<String> membersWithEdges = edges.stream()
                .map(OrphanEdgeScanServiceImpl::upstream)
                .collect(Collectors.toCollection(HashSet::new));
        List<String> withoutEdges = busTokens.stream()
                .filter(token -> !membersWithEdges.contains(token))
                .sorted()
                .collect(Collectors.toList());

        return new OrphanEdgeScanReport(List.copyOf(armed), List.copyOf(orphans),
                deadEdges, withoutEdges, covered);
    }

    /**
     * True when this edge row names the given referencing table under any of
     * the accepted spellings; downstream resolution is single-sourced
     * through {@link LinkageObjectTables#resolve}.
     */
    private static boolean covers(Map<String, Object> edge, String referencingTable) {
        String downstream = downstream(edge);
        if (downstream == null || downstream.isBlank()) {
            return false;
        }
        String target = referencingTable.toLowerCase();
        String direct = downstream.toLowerCase();
        if (target.equals(direct) || target.equals("tp_" + direct)) {
            return true;
        }
        return LinkageObjectTables.resolve(downstream).equalsIgnoreCase(referencingTable);
    }

    private static String edgeKey(Map<String, Object> edge) {
        return (String) edge.get("edge_key");
    }

    private static String upstream(Map<String, Object> edge) {
        return (String) edge.get("upstream_object_type");
    }

    private static String downstream(Map<String, Object> edge) {
        return (String) edge.get("downstream_object_type");
    }

    private static boolean isNonExempt(Map<String, Object> edge) {
        Object flag = edge.get("exempt");
        return flag instanceof Number number && number.intValue() == 0;
    }
}
