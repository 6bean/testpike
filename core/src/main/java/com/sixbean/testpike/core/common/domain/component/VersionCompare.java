package com.sixbean.testpike.core.common.domain.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This common component performs version comparison and range satisfaction checks, extracted from the version detection system.
 * The original private implementation in AppautoEnvDoctorServiceImpl was moved to shared lower-layer code after being reused at least twice, because duplicate logic is prohibited.
 * The semantics are identical to the existing engine health-check surface, comparing x.y[.z] segment by segment numerically, padding shorter segments with zeros, and treating non-numeric segments as 0 to conservatively judge lower.
 * Range expressions recognize only the &gt;= prefix, such as {@code >=22.12}, and without that token they use equality semantics for pinned-version reconciliation.
 */
public final class VersionCompare {

    private static final Pattern FIRST_VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    private VersionCompare() {
    }

 /** Extract the first version number from free text such as ".1 (x64)" and fall back to "0" when no version number is present. */
    public static String firstVersionOf(String text) {
        if (text == null) {
            return "0";
        }
        Matcher m = FIRST_VERSION.matcher(text);
        return m.find() ? m.group() : "0";
    }

    /** Compare version x.y[.z] segment by segment numerically, padding shorter segments with zeros and treating non-numeric segments as 0 to conservatively judge lower. */
    public static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? digitsOrZero(pa[i]) : 0;
            int vb = i < pb.length ? digitsOrZero(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    /**
     * This checks whether the current version satisfies the range expression, using lower-bound semantics for a {@code >=x} prefix and equality semantics for pinned versions when that token is absent.
     * If the expression becomes empty after removing non-version characters, no gate is set and the condition is always true.
     */
    public static boolean satisfies(String actual, String requirement) {
        String want = requirement.replaceAll("[^0-9.]", "");
        if (want.isEmpty()) {
            return true;
        }
        int cmp = compare(firstVersionOf(actual), want);
        return requirement.contains(">=") ? cmp >= 0 : cmp == 0;
    }

    private static int digitsOrZero(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
