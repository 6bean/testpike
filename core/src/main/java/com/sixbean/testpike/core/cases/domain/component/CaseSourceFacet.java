package com.sixbean.testpike.core.cases.domain.component;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth that folds the persisted provenance columns of a case
 * (source + source_ref) into the four facets the review queue and the case list
 * show to users: in-product conversation, external agent channel, bulk import,
 * manual. Derived once server-side so no page translates the codes on its own.
 *
 * <p>Prefix rules are intentionally narrow: only references that start with the
 * {@code mcp} or {@code chat} markers change a facet, so clone references
 * ({@code case:{id}}) and free-form notes fall back to manual as decided.
 */
public final class CaseSourceFacet {

    /** Case landed from an in-product AI conversation draft. */
    public static final String CHAT = "chat";
    /** Case created or written back through the external agent (MCP) surface. */
    public static final String OFF_AGENT = "off_agent";
    /** Case brought in from templates or a legacy bulk import. */
    public static final String IMPORT = "import";
    /** Everything else, including plain UI creation and unmarked AI rows. */
    public static final String MANUAL = "manual";

    /** The four facet values a list query may filter by. */
    public static final Set<String> FACET_VALUES = Set.of(CHAT, OFF_AGENT, IMPORT, MANUAL);

    /**
     * SQL mirror of {@link #of(int, String)} over the persisted columns, for the
     * server-side facet filter on the paginated case list. It must stay exactly
     * equivalent — CaseSourceFacetQueryTest pins both against real rows, so a
     * drift between the two single-source sides is a red test, not a silent one.
     * Marker comparison lowercases and trims like the Java side does; the
     * underscore LIKE pattern is escaped so it matches a literal underscore
     * rather than any character.
     */
    public static final String FACET_CASE_SQL =
            "CASE WHEN (" + refMarkerSql("mcp") + ") THEN '" + OFF_AGENT + "' "
                    + "WHEN (" + refMarkerSql(CHAT) + ") THEN '" + CHAT + "' "
                    + "WHEN source IN (2, 3) THEN '" + IMPORT + "' "
                    + "ELSE '" + MANUAL + "' END";

    private static String refMarkerSql(String marker) {
        String ref = "lower(btrim(COALESCE(source_ref, '')))";
        return ref + " = '" + marker + "'"
                + " OR " + ref + " LIKE '" + marker + ":%'"
                + " OR " + ref + " LIKE '" + marker + "-%'"
                + " OR " + ref + " LIKE '" + marker + "\\_%' ESCAPE '\\'";
    }

    private CaseSourceFacet() {
    }

    /**
     * Map persisted provenance to a facet value.
     *
     * @param source    1=AI generated 2=template 3=legacy import 4=clone 5=explore 6=sandbox
     * @param sourceRef free-form provenance reference, may be null
     * @return one of {@link #CHAT}, {@link #OFF_AGENT}, {@link #IMPORT}, {@link #MANUAL}
     */
    public static String of(int source, String sourceRef) {
        String ref = sourceRef == null ? "" : sourceRef.trim().toLowerCase(Locale.ROOT);
        if (ref.equals("mcp") || ref.startsWith("mcp:") || ref.startsWith("mcp-") || ref.startsWith("mcp_")) {
            return OFF_AGENT;
        }
        if (ref.equals(CHAT) || ref.startsWith("chat:") || ref.startsWith("chat-") || ref.startsWith("chat_")) {
            return CHAT;
        }
        if (source == 2 || source == 3) {
            return IMPORT;
        }
        return MANUAL;
    }
}
