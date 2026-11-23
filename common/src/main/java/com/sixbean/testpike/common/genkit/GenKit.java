package com.sixbean.testpike.common.genkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;


public final class GenKit {

    
    public static final Set<String> KNOWN_AREA_CODES = Set.of(
            "110101", "110105", "120101", "310101", "310104", "310115",
            "320102", "330102", "330106", "340102", "440103", "440305",
            "440307", "500103", "510104", "610102");

    
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    
    private static final char[] ID_CHECK_CHARS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    
    private static final String USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    
    private static final String[] PHONE_PREFIXES = {
            "133", "153", "180", "181", "189", "130", "131", "132", "155", "156",
            "185", "186", "134", "135", "136", "137", "138", "139", "150", "151", "152", "157", "158"};

    
    private static final String[] CARD_BINS = {"622202", "621226", "622848", "621483", "628288"};

    private static final String[] SURNAMES = {"赵", "钱", "孙", "李", "周", "吴", "郑", "王",
            "冯", "陈", "蒋", "沈", "韩", "杨", "朱", "秦", "许", "何", "吕", "施"};

    private static final String[] GIVEN_CHARS = {"伟", "芳", "娜", "秀英", "敏", "静", "丽", "强",
            "磊", "军", "洋", "勇", "艳", "杰", "娟", "涛", "明", "超", "秀兰", "霞", "平", "刚", "桂英"};

    private static final String[] EMAIL_DOMAINS = {"example.com", "test.org", "demo.cn", "mail.test"};

    
    private static final String[] COMPANY_CITIES = {"深圳", "上海", "北京", "广州", "杭州", "南京", "成都", "武汉"};
    private static final String[] COMPANY_WORDS = {"鑫源", "华腾", "联合", "鼎信", "恒润", "天泽", "睿达", "中科"};
    private static final String[] COMPANY_TRADES = {"科技", "信息", "贸易", "网络", "智能装备", "数据服务"};

    
    private static final String[] ADDRESS_CITIES = {"深圳市", "上海市", "北京市", "广州市", "杭州市", "南京市", "成都市", "武汉市"};
    private static final String[] ADDRESS_DISTRICTS = {"南山区", "浦东新区", "朝阳区", "天河区", "西湖区", "鼓楼区", "武侯区", "洪山区"};
    private static final String[] ADDRESS_ROADS = {"中山路", "科技园南路", "人民大道", "解放路", "望江路", "和平街", "创新大道", "滨江路"};

    
    private static final String[] PLATE_PROVINCES = {"京", "津", "沪", "粤", "苏", "浙", "鲁", "川"};
    private static final String PLATE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenKit() {
    }


    
    public static String idCard(Long seed) {
        Random random = randomFor(seed);
        String area = pick(KNOWN_AREA_CODES.toArray(new String[0]), random);
        int year = 1950 + random.nextInt(56);
        int month = 1 + random.nextInt(12);
        int day = 1 + random.nextInt(28);
        String birth = String.format(Locale.ROOT, "%04d%02d%02d", year, month, day);
        StringBuilder seq = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            seq.append(random.nextInt(10));
        }
        String first17 = area + birth + seq;
        return first17 + idCardCheckDigit(first17);
    }

    
    public static char idCardCheckDigit(String first17) {
        if (first17 == null || first17.length() != 17 || !first17.chars().allMatch(Character::isDigit)) {
            throw new ServiceException("身份证前 17 位必须是纯数字，无法计算校验位");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (first17.charAt(i) - '0') * ID_WEIGHTS[i];
        }
        return ID_CHECK_CHARS[sum % 11];
    }

    
    public static String bankCard(Long seed) {
        Random random = randomFor(seed);
        StringBuilder digits = new StringBuilder(pick(CARD_BINS, random));
        while (digits.length() < 18) {
            digits.append(random.nextInt(10));
        }
        return digits.append(luhnCheckDigit(digits.toString())).toString();
    }

    
    public static char luhnCheckDigit(String partialDigits) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = partialDigits.length() - 1; i >= 0; i--) {
            int d = partialDigits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    
    public static boolean luhnValid(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty() || !cardNumber.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return cardNumber.charAt(cardNumber.length() - 1)
                == luhnCheckDigit(cardNumber.substring(0, cardNumber.length() - 1));
    }

    
    public static String uscc(Long seed) {
        Random random = randomFor(seed);
        StringBuilder code = new StringBuilder(18);
        code.append("9");
        code.append("1");
        code.append(pick(KNOWN_AREA_CODES.toArray(new String[0]), random));
        for (int i = 0; i < 9; i++) {
            code.append(USCC_CHARS.charAt(random.nextInt(USCC_CHARS.length())));
        }
        return code.append(usccCheckChar(code.toString())).toString();
    }

    
    public static char usccCheckChar(String first17) {
        if (first17 == null || first17.length() != 17) {
            throw new ServiceException("统一社会信用代码前 17 位长度必须为 17");
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int value = USCC_CHARS.indexOf(first17.charAt(i));
            if (value < 0) {
                throw new ServiceException("统一社会信用代码含非法字符（禁用 I/O/S/Z/V）");
            }
            sum += value * USCC_WEIGHTS[i];
        }
        int check = 31 - sum % 31;
        return USCC_CHARS.charAt(check == 31 ? 0 : check);
    }

    
    public static String phone(Long seed) {
        Random random = randomFor(seed);
        StringBuilder phone = new StringBuilder(pick(PHONE_PREFIXES, random));
        while (phone.length() < 11) {
            phone.append(random.nextInt(10));
        }
        return phone.toString();
    }

    
    public static String chineseName(Long seed) {
        Random random = randomFor(seed);
        return pick(SURNAMES, random) + pick(GIVEN_CHARS, random);
    }

    
    public static String email(Long seed) {
        Random random = randomFor(seed);
        StringBuilder local = new StringBuilder();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 8; i++) {
            local.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return local + "@" + pick(EMAIL_DOMAINS, random);
    }

    
    public static String companyName(Long seed) {
        Random random = randomFor(seed);
        return pick(COMPANY_CITIES, random) + "市" + pick(COMPANY_WORDS, random)
                + pick(COMPANY_TRADES, random) + "有限公司";
    }

    
    public static String address(Long seed) {
        Random random = randomFor(seed);
        return pick(ADDRESS_CITIES, random) + pick(ADDRESS_DISTRICTS, random)
                + pick(ADDRESS_ROADS, random) + (1 + random.nextInt(999)) + "号";
    }

    
    public static String lngLat(Long seed) {
        Random random = randomFor(seed);
        double lng = 73.5 + random.nextDouble() * (135.1 - 73.5);
        double lat = 18.1 + random.nextDouble() * (53.6 - 18.1);
        return String.format(Locale.ROOT, "%.6f,%.6f", lng, lat);
    }

    
    public static String plateNumber(Long seed) {
        Random random = randomFor(seed);
        StringBuilder plate = new StringBuilder(pick(PLATE_PROVINCES, random));
        plate.append(PLATE_LETTERS.charAt(random.nextInt(PLATE_LETTERS.length())));
        String pool = PLATE_LETTERS + "0123456789";
        for (int i = 0; i < 5; i++) {
            plate.append(pool.charAt(random.nextInt(pool.length())));
        }
        return plate.toString();
    }

    
    public static String iban(Long seed) {
        Random random = randomFor(seed);
        StringBuilder bban = new StringBuilder(18);
        for (int i = 0; i < 18; i++) {
            bban.append(random.nextInt(10));
        }
        return "DE" + ibanCheckDigits("DE", bban.toString()) + bban;
    }

    
    public static int ibanRemainder(String iban) {
        if (iban == null || iban.length() < 5) {
            throw new ServiceException("IBAN 长度不足，无法校验");
        }
        String normalized = (iban.substring(4) + iban.substring(0, 4))
                .toUpperCase(Locale.ROOT).replace(" ", "");
        StringBuilder digits = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                digits.append(c - 'A' + 10);
            } else {
                throw new ServiceException("IBAN 含非法字符，无法校验");
            }
        }
        int remainder = 0;
        for (char d : digits.toString().toCharArray()) {
            remainder = (remainder * 10 + (d - '0')) % 97;
        }
        return remainder;
    }

    
    public static boolean ibanValid(String iban) {
        try {
            return ibanRemainder(iban) == 1;
        } catch (ServiceException e) {
            return false;
        }
    }

    
    private static String ibanCheckDigits(String countryCode, String bban) {
        int remainder = ibanDigitsRemainder(bban + countryCode + "00");
        int check = 98 - remainder;
        return String.format(Locale.ROOT, "%02d", check);
    }

    
    private static int ibanDigitsRemainder(String expandedDigits) {
        int remainder = 0;
        for (char c : expandedDigits.toCharArray()) {
            int d;
            if (Character.isDigit(c)) {
                d = c - '0';
            } else if (Character.isLetter(c)) {
                int value = Character.toUpperCase(c) - 'A' + 10;
                remainder = (remainder * 100 + value) % 97;
                continue;
            } else {
                throw new ServiceException("IBAN 计算含非法字符");
            }
            remainder = (remainder * 10 + d) % 97;
        }
        return remainder;
    }


    
    public static String dateTime(String format) {
        String pattern = format == null || format.isBlank() ? "yyyy-MM-dd HH:mm:ss" : format;
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    
    public static String jsonFormat(String text) {
        return renderJson(text, true);
    }

    
    public static String jsonMinify(String text) {
        return renderJson(text, false);
    }

    
    public static String timestampToDate(String epochSeconds, String format, String zone) {
        if (epochSeconds == null || epochSeconds.isBlank()) {
            throw new ServiceException("时间戳为空，无法转换为日期");
        }
        long value;
        try {
            value = Long.parseLong(epochSeconds.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("时间戳必须是纯数字（秒或毫秒），无法转换");
        }
        if (Math.abs(value) >= 1_000_000_000_000L) {
            value = value / 1000L;
        }
        String pattern = format == null || format.isBlank() ? "yyyy-MM-dd HH:mm:ss" : format;
        java.time.ZoneId zoneId = zone == null || zone.isBlank()
                ? java.time.ZoneOffset.UTC : java.time.ZoneId.of(zone.trim());
        return java.time.Instant.ofEpochSecond(value).atZone(zoneId)
                .format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    
    public static String jsonPathExtract(String text, String path) {
        if (path == null || path.isBlank()) {
            throw new ServiceException("jsonPathExtract 缺少必填参数 path");
        }
        JsonNode node = readJsonTree(text);
        String rest = path.startsWith("$.") ? path.substring(2)
                : path.startsWith("$") ? path.substring(1) : path;
        try {
            for (String part : rest.split("\\.")) {
                if (part.isEmpty()) {
                    continue;
                }
                int bracket = part.indexOf('[');
                String name = bracket < 0 ? part : part.substring(0, bracket);
                if (!name.isEmpty()) {
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new ServiceException("JSONPath 路径「" + path + "」含不支持的语法段");
                    }
                    node = node.get(name);
                    if (node == null) {
                        throw new ServiceException("JSONPath 「" + path + "」在文档中未命中");
                    }
                }
                java.util.regex.Matcher idx = java.util.regex.Pattern.compile("\\[(\\d+)]")
                        .matcher(bracket < 0 ? "" : part.substring(bracket));
                while (idx.find()) {
                    node = node.get(Integer.parseInt(idx.group(1)));
                    if (node == null) {
                        throw new ServiceException("JSONPath 「" + path + "」在文档中未命中");
                    }
                }
                if (bracket >= 0 && !part.substring(bracket).matches("(\\[\\d+])+")) {
                    throw new ServiceException("JSONPath 路径「" + path + "」含不支持的语法段");
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("JSONPath 「" + path + "」在文档中未命中");
        }
        if (node.isContainerNode()) {
            throw new ServiceException("JSONPath 「" + path + "」取到的是对象/数组，本函数仅支持标量值");
        }
        return node.asText();
    }

    
    public static String flatten(String text) {
        StringBuilder out = new StringBuilder();
        flattenInto(readJsonTree(text), "", out);
        return out.toString();
    }

    private static void flattenInto(JsonNode node, String prefix, StringBuilder out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    flattenInto(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenInto(node.get(i), prefix + "[" + i + "]", out);
            }
        } else {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(prefix).append('=').append(node.asText());
        }
    }

    
    public static String camelCase(String text) {
        if (text == null) {
            throw new ServiceException("待转换文本为空");
        }
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (char c : text.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ' || c == '\t') {
                upperNext = out.length() > 0;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    
    public static String trim(String text) {
        if (text == null) {
            throw new ServiceException("待处理文本为空");
        }
        return text.strip();
    }

    
    public static String wordCount(String text) {
        if (text == null || text.isBlank()) {
            return "0";
        }
        return String.valueOf(text.trim().split("\\s+").length);
    }

    
    public static String regexMatch(String text, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new ServiceException("regexMatch 缺少必填参数 pattern");
        }
        java.util.regex.Matcher m;
        try {
            m = java.util.regex.Pattern.compile(pattern).matcher(text == null ? "" : text);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ServiceException("正则表达式不合法，请检查后重试");
        }
        if (!m.find()) {
            throw new ServiceException("正则「" + pattern + "」在文本中未命中");
        }
        return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
    }

    private static String renderJson(String text, boolean pretty) {
        Object tree = readJsonTree(text);
        try {
            return pretty ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
                    : MAPPER.writeValueAsString(tree);
        } catch (Exception e) {
            throw new ServiceException("文本不是合法的 JSON，请检查后重试");
        }
    }

    
    private static JsonNode readJsonTree(String text) {
        if (text == null || text.isBlank()) {
            throw new ServiceException("JSON 文本为空，不是合法的 JSON，无法处理");
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new ServiceException("文本不是合法的 JSON，请检查后重试");
        }
    }


    public static String md5(String text) {
        return digestHex("MD5", text);
    }

    public static String sha256(String text) {
        return digestHex("SHA-256", text);
    }

    
    public static String sha1(String text) {
        return digestHex("SHA-1", text);
    }

    private static String digestHex(String algorithm, String text) {
        if (text == null) {
            throw new ServiceException("待摘要文本为空");
        }
        try {
            byte[] hash = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException("摘要计算失败：" + algorithm);
        }
    }

    public static String base64Encode(String text) {
        if (text == null) {
            throw new ServiceException("待编码文本为空");
        }
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64Decode(String text) {
        if (text == null || text.isBlank()) {
            throw new ServiceException("待解码文本为空，不是合法的 Base64");
        }
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("文本不是合法的 Base64，请检查后重试");
        }
    }

    
    public static String urlEncode(String text) {
        if (text == null) {
            throw new ServiceException("待编码文本为空");
        }
        try {
            return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new ServiceException("URL 编码失败，请检查文本");
        }
    }

    
    public static String unicodeEscape(String text) {
        if (text == null) {
            throw new ServiceException("待转义文本为空");
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 0x80) {
                out.append(c);
            } else {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    
    public static String radix(String text, String fromBase, String toBase) {
        if (text == null || text.isBlank()) {
            throw new ServiceException("待转换数字串为空");
        }
        int from = parseRadixBase(fromBase, 10);
        int to = parseRadixBase(toBase, 0);
        try {
            return Long.toString(Long.parseLong(text.trim(), from), to).toLowerCase(Locale.ROOT);
        } catch (NumberFormatException e) {
            throw new ServiceException("「" + text + "」不是合法的 " + from + " 进制数，或目标进制超出 2-36");
        }
    }

    private static int parseRadixBase(String base, int fallback) {
        int value = base == null || base.isBlank() ? fallback : Integer.parseInt(base.trim());
        if (value < 2 || value > 36) {
            throw new ServiceException("进制必须在 2 到 36 之间");
        }
        return value;
    }

    // ------------------------------------------------------------------ Signature family

    /** Current epoch time as text: 10-digit seconds (default) or 13-digit milliseconds. */
    public static String timestamp(String unit) {
        if (unit == null || unit.isBlank() || "s".equals(unit)) {
            return Long.toString(System.currentTimeMillis() / 1000);
        }
        if ("ms".equals(unit)) {
            return Long.toString(System.currentTimeMillis());
        }
        throw new ServiceException("timestamp 的 unit 只能是 s 或 ms：" + unit);
    }

    /** Random lowercase alphanumeric nonce (default length 16, range 8-64); seed makes it reproducible. */
    public static String nonce(Long seed, String length) {
        int size = 16;
        if (length != null && !length.isBlank()) {
            try {
                size = Integer.parseInt(length.trim());
            } catch (NumberFormatException e) {
                throw new ServiceException("nonce 的 length 必须是数字：" + length);
            }
            if (size < 8 || size > 64) {
                throw new ServiceException("nonce 的 length 必须在 8 到 64 之间");
            }
        }
        Random random = randomFor(seed);
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** Re-order k=v pairs lexicographically and join with the separator (default &). */
    public static String sortConcat(String text, String separator) {
        if (text == null || text.isBlank()) {
            throw new ServiceException("sortConcat 缺少 k=v 参数串");
        }
        String sep = separator == null || separator.isEmpty() ? "&" : separator;
        List<String> pairs = new ArrayList<>();
        for (String piece : text.split(java.util.regex.Pattern.quote(sep))) {
            if (!piece.isBlank()) {
                pairs.add(piece.trim());
            }
        }
        pairs.sort(java.util.Comparator.naturalOrder());
        return String.join(sep, pairs);
    }

    /** HMAC-SHA256 over UTF-8 text keyed by key, lowercase hex. Key arrives via @cred resolution upstream. */
    public static String hmacSha256(String text, String key) {
        if (text == null) {
            throw new ServiceException("待签名文本为空");
        }
        if (key == null || key.isEmpty()) {
            throw new ServiceException("hmacSha256 缺少密钥 key（认证模板侧请引用 @cred，禁明文密钥入模板）");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new ServiceException("HMAC-SHA256 计算失败");
        }
    }


    
    public static String invoke(String name, Map<String, Object> args) {
        GenFunction fn = GenFunction.byName(name);
        if (fn == null) {
            throw new ServiceException("genkit 未注册的函数 " + name
                    + "，当前可用函数：" + String.join("、", GenFunction.allNames()));
        }
        Map<String, Object> safe = args == null ? Map.of() : args;
        Long seed = seedOf(safe);
        return switch (fn) {
            case ID_CARD -> idCard(seed);
            case BANK_CARD -> bankCard(seed);
            case USCC -> uscc(seed);
            case PHONE -> phone(seed);
            case CHINESE_NAME -> chineseName(seed);
            case EMAIL -> email(seed);
            case DATE_TIME -> dateTime(stringOf(safe, "format"));
            case MD5 -> md5(requiredText(safe, name));
            case SHA256 -> sha256(requiredText(safe, name));
            case BASE64_ENCODE -> base64Encode(requiredText(safe, name));
            case BASE64_DECODE -> base64Decode(requiredText(safe, name));
            case JSON_FORMAT -> jsonFormat(requiredText(safe, name));
            case JSON_MINIFY -> jsonMinify(requiredText(safe, name));
            case COMPANY_NAME -> companyName(seed);
            case ADDRESS -> address(seed);
            case LNG_LAT -> lngLat(seed);
            case PLATE_NUMBER -> plateNumber(seed);
            case IBAN -> iban(seed);
            case SHA1 -> sha1(requiredText(safe, name));
            case URL_ENCODE -> urlEncode(requiredText(safe, name));
            case UNICODE_ESCAPE -> unicodeEscape(requiredText(safe, name));
            case RADIX -> radix(requiredText(safe, name), stringOf(safe, "from"), stringOf(safe, "to"));
            case TIMESTAMP_TO_DATE -> timestampToDate(requiredText(safe, name),
                    stringOf(safe, "format"), stringOf(safe, "zone"));
            case JSON_PATH_EXTRACT -> jsonPathExtract(requiredText(safe, name), stringOf(safe, "path"));
            case FLATTEN -> flatten(requiredText(safe, name));
            case CAMEL_CASE -> camelCase(requiredText(safe, name));
            case TRIM -> trim(requiredText(safe, name));
            case WORD_COUNT -> wordCount(requiredText(safe, name));
            case REGEX_MATCH -> regexMatch(requiredText(safe, name), stringOf(safe, "pattern"));
            case TIMESTAMP -> timestamp(stringOf(safe, "unit"));
            case NONCE -> nonce(seed, stringOf(safe, "length"));
            case SORT_CONCAT -> sortConcat(requiredText(safe, name), stringOf(safe, "separator"));
            case HMAC_SHA256 -> hmacSha256(requiredText(safe, name), stringOf(safe, "key"));
            // Read-only analysis family: served by the dialog analysis tool on the server,
            // never evaluable from a data-fabrication template (fail-closed by design).
            case COVERAGE_SUMMARY, ASSERTION_DENSITY, REHEARSAL_DRAFT,
                    REHEARSAL_RISK_REPORT -> throw new ServiceException(
                    name + " 是只读分析函数，只能在 AI 对话中由服务端分析工具执行，不能用于造数模板求值");
        };
    }

    private static Long seedOf(Map<String, Object> args) {
        Object seed = args.get("seed");
        if (seed == null) {
            return null;
        }
        if (seed instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(seed.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("seed 必须是数字，模板函数才能按种子复现");
        }
    }

    private static String stringOf(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }

    private static String requiredText(Map<String, Object> args, String name) {
        String text = stringOf(args, "text");
        if (text == null) {
            throw new ServiceException("函数 " + name + " 缺少必填参数 text");
        }
        return text;
    }


    private static Random randomFor(Long seed) {
        return seed == null ? new Random() : new Random(seed);
    }

    private static String pick(String[] pool, Random random) {
        List<String> list = new ArrayList<>(List.of(pool));
        return list.get(random.nextInt(list.size()));
    }
}
