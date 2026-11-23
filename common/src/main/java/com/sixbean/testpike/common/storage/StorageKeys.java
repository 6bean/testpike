package com.sixbean.testpike.common.storage;


public final class StorageKeys {

    private StorageKeys() {
    }

 
    private static final java.util.Set<String> SCENES = java.util.Set.of(
            "script", "import-export", "recording", "skill-pack", "tool-pack", "exec-artifact", "explore",
            "comment-attachment", "visual-baseline", "app-package", "judge-artifact", "mock-draft",
            "perf-dataset", "perf-artifact", "perf-script", "chat-image", "tool-result",
            "appauto-element");

 
    private static final int MAX_STEM = 64;

    
    private static final int UUID_PREFIX = 8;

    
    public static String build(String prefix, long projectId, String scene, String filename) {
        if (scene == null || !SCENES.contains(scene)) {
            throw new IllegalArgumentException("未知存储场景: " + scene);
        }
        String ym = java.time.YearMonth.now(java.time.ZoneId.systemDefault())
                .toString().replace("-", "");
        String uid = java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, UUID_PREFIX);
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            sb.append(trimSlashes(prefix)).append('/');
        }
        sb.append(projectId).append('/').append(scene).append('/').append(ym)
                .append('/').append(uid).append('-').append(sanitize(filename));
        return sb.toString();
    }

    
    static String sanitize(String filename) {
        String base = filename == null ? "" : filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            ext = base.substring(dot);
            base = base.substring(0, dot);
        }
        String stem = cleanRun(base);
        if (stem.length() > MAX_STEM) {
            stem = stem.substring(0, MAX_STEM);
        }
        if (stem.isEmpty() || stem.equals("..")) {
            stem = "file";
        }
        String safeExt = ext.length() - 1 <= 16 ? cleanRun(ext) : "";
        return safeExt.isEmpty() || ".".equals(safeExt) ? stem : stem + safeExt;
    }

    
    private static String cleanRun(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (ok && c == '.' && !out.isEmpty() && out.charAt(out.length() - 1) == '.') {
                ok = false;
            }
            if (ok) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String trimSlashes(String s) {
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '/') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '/') {
            b--;
        }
        return s.substring(a, b);
    }
}
