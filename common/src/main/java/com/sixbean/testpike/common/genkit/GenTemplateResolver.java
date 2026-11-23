package com.sixbean.testpike.common.genkit;

import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class GenTemplateResolver {

    
    static final Pattern GEN_PATTERN =
            Pattern.compile("\\$\\{gen\\.([A-Za-z][A-Za-z0-9]*)(?:\\((.*?)\\))?\\}");

    private GenTemplateResolver() {
    }

    
    public static String resolve(String text, List<Map<String, Object>> seedEvents) {
        if (text == null || text.isEmpty() || !GEN_PATTERN.matcher(text).find()) {
            return text;
        }
        Matcher m = GEN_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Map<String, Object> args = parseArgs(m.group(2));
            GenFunction fn = GenFunction.byName(name);
            if (fn != null && fn.isRandom()) {
                if (!args.containsKey("seed") || String.valueOf(args.get("seed")).isBlank()) {
                    args.put("seed", newRandomSeed());
                }
                if (seedEvents != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("fn", name);
                    event.put("seed", toSeedLong(args.get("seed")));
                    seedEvents.add(event);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(GenKit.invoke(name, args)));
        }
        m.appendTail(out);
        return out.toString();
    }

    
    static Map<String, Object> parseArgs(String raw) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return args;
        }
        String currentKey = null;
        for (String segment : raw.split(",")) {
            Matcher m = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)=(.*)$", Pattern.DOTALL).matcher(segment);
            if (m.matches()) {
                currentKey = m.group(1);
                args.put(currentKey, m.group(2));
            } else if (currentKey != null) {
                args.put(currentKey, args.get(currentKey) + "," + segment);
            } else if (!segment.isBlank()) {
                throw new ServiceException("gen 函数参数「" + segment.trim() + "」不是 k=v 形态，无法解析");
            }
        }
        return args;
    }

    
    static long newRandomSeed() {
        return new Random().nextLong(1_000_000_000_000_000L);
    }

    private static long toSeedLong(Object seed) {
        if (seed instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(seed));
        } catch (NumberFormatException e) {
            throw new ServiceException("seed 必须是数字，模板函数才能按种子复现");
        }
    }

    
    public static String seedsAsJson(List<Map<String, Object>> seedEvents) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> event : seedEvents) {
            items.add("{\"fn\": \"" + event.get("fn") + "\", \"seed\": " + event.get("seed") + "}");
        }
        return "[" + String.join(", ", items) + "]";
    }
}
