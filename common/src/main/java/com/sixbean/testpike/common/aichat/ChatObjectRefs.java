package com.sixbean.testpike.common.aichat;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Canonicalisation of the dialog reference chips .
 * <p>This is the SINGLE validation point of the (kind, id, title) triple:
 * admin only mirrors the wire shape, core canonicalises once when opening the
 * run (bad chips refuse the run with a named reason before anything lands),
 * ai-service consumes the canonical list without re-checking. The adjudicated
 * rule "over-permission references are refused + journaled" is enforced here
 * together with the project-slice check on the core side — a chip pointing at
 * another project's row is a horizontal-access attempt (H1 red line).
 * <p>Posture details pinned by {@code ChatObjectRefsTest}: trimmed titles cut
 * at the platform title column width (200), dedup by (kind, id) keeping the
 * first arrival, unknown kinds refused BY NAME (never silently dropped — a
 * dropped chip would make the answer differ from what the user pinned), and
 * exceeding the count cap is a refusal naming both numbers (never a
 * truncation, same fail-loud posture as PromptSizeGuard).
 */
public final class ChatObjectRefs {

    /**
 * Adjudicated reference families (E3: case / defect / batch / doc;
 * AO batch stage 1 widens the channel with skill / tool so the
 * @-mention chips can pin exactly what the core assembly doors offer).
 */
    public static final Set<String> KINDS =
            Set.of("case", "defect", "batch", "doc", "skill", "tool");

    /** Bound of tp_* title/name columns — longer labels are cut, not refused. */
    public static final int TITLE_MAX_CHARS = 200;

    /** K8 key of the per-question chip cap (hot-updatable; core resolves it). */
    public static final String KEY_MAX_REFS = "ai.chat.ref.max-objects";
    /** Seeded default when the key is unreadable. */
    public static final int DEFAULT_MAX_REFS = 10;

    private ChatObjectRefs() {
    }

    /**
     * Validate + normalise one request's chips.
     *
     * @param refs     raw triples from the wire, null/empty = no references
     * @param maxCount hot-read cap resolved by the caller (K8 {@link #KEY_MAX_REFS})
     * @return an immutable, deduplicated, title-bounded list (never null)
     * @throws ServiceException naming the offending value on any rule breach
     */
    public static List<ChatObjectRef> canonicalize(List<ChatObjectRef> refs, int maxCount) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        if (refs.size() > maxCount) {
            throw new ServiceException("单条提问最多引用 " + maxCount + " 个对象，当前 "
                    + refs.size() + " 个，请减少引用后再发送。", BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ChatObjectRef> out = new ArrayList<>(refs.size());
        for (ChatObjectRef ref : refs) {
            if (ref == null) {
                continue;
            }
            String kind = ref.kind() == null ? "" : ref.kind().trim().toLowerCase();
            if (!KINDS.contains(kind)) {
                throw new ServiceException("引用对象类型不受支持：" + ref.kind()
                        + "（仅支持 case、defect、batch、doc、skill、tool）。",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (ref.id() <= 0) {
                throw new ServiceException("引用对象缺少有效的记录编号（" + kind + "）。",
                        BizErrorCode.VALIDATION_ERROR);
            }
            String title = ref.title() == null ? "" : ref.title().trim();
            if (title.isEmpty()) {
                throw new ServiceException("引用对象缺少名称（" + kind + " #" + ref.id()
                        + "），请重新选择该引用。", BizErrorCode.VALIDATION_ERROR);
            }
            if (title.length() > TITLE_MAX_CHARS) {
                title = title.substring(0, TITLE_MAX_CHARS);
            }
            if (seen.add(kind + '#' + ref.id())) {
                out.add(new ChatObjectRef(kind, ref.id(), title));
            }
        }
        return List.copyOf(out);
    }
}
