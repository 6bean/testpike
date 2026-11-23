package com.sixbean.testpike.core.aichat.domain.component;

/**
 * Share lifetime window for dialog snapshot links .
 * One place owns the two bounds so the create door, the screen copy and the
 * tests never carry three copies of the same number that can drift apart.
 */
public final class ChatShareTtlWindow {

    /** Shortest life: below an hour a link is more likely to expire mid-read
     *  than to be useful; someone pasting it into a chat already means hours. */
    public static final int MIN_HOURS = 1;

    /** Longest life: a share hands masked content to a link holder — beyond a
     *  week it behaves like a public page, which the design rules out. */
    public static final int MAX_HOURS = 168;

    /** Clamp any requested ttl into [{@link #MIN_HOURS}, {@link #MAX_HOURS}]:
     *  out-of-range requests settle on the nearest bound instead of failing,
     *  because the user asked for "about this long", not for a specific number. */
    public static int clamp(int hours) {
        return Math.max(MIN_HOURS, Math.min(MAX_HOURS, hours));
    }

    private ChatShareTtlWindow() {
    }
}
