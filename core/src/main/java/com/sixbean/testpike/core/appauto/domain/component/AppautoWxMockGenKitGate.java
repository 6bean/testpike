package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockPruneResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
public class AppautoWxMockGenKitGate {

    
    private static final String PLACEHOLDER_PREFIX = "${";

    
    public List<String> randomFamilyManifest() {
        List<String> manifest = new ArrayList<>();
        for (GenFunction f : GenFunction.values()) {
            if (f.isRandom()) {
                manifest.add(f.functionName());
            }
        }
        return manifest;
    }

    
    public AppautoWxMockPruneResult prune(List<Map<String, Object>> mocks) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> row : mocks) {
            List<String> rowReasons = new ArrayList<>();
            checkValue(row.get("response"), "response", rowReasons);
            if (rowReasons.isEmpty()) {
                kept.add(row);
            } else {
                String scenario = String.valueOf(row.get("scenario"));
                rowReasons.forEach(r -> reasons.add("场景「" + scenario + "」" + r));
            }
        }
        return new AppautoWxMockPruneResult(kept, mocks.size() - kept.size(), reasons);
    }

    
    private void checkValue(Object node, String path, List<String> reasons) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                checkField(key, entry.getValue(), path + "." + key, reasons);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkValue(list.get(i), path + "[" + i + "]", reasons);
            }
        }
    }

    
    private void checkField(String key, Object value, String path, List<String> reasons) {
        if (value instanceof Map || value instanceof List) {
            checkValue(value, path, reasons);
            return;
        }
        if (!(value instanceof String text)) {
            return;
        }
        if (text.startsWith(PLACEHOLDER_PREFIX)) {
            return;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("idcard") || key.contains("身份证")) {
            judgeIdCard(text, path, reasons);
        } else if (lower.contains("bankcard") || lower.contains("cardno") || key.contains("银行卡")) {
            judgeBankCard(text, path, reasons);
        } else if (lower.contains("uscc") || lower.contains("creditcode") || key.contains("信用代码")) {
            judgeUscc(text, path, reasons);
        } else if (lower.contains("iban")) {
            judgeIban(text, path, reasons);
        }
    }

    
    private void judgeIdCard(String text, String path, List<String> reasons) {
        if (!text.matches("\\d{17}[0-9Xx]")) {
            return;
        }
        if (!String.valueOf(GenKit.idCardCheckDigit(text.substring(0, 17)))
                .equalsIgnoreCase(String.valueOf(text.charAt(17)))) {
            reasons.add("字段 " + path + " 身份证号校验位不符 GB11643（疑似模型编造假号，"
                    + "请改用 ${gen.idCard(seed=数字)} 占位符）");
        }
    }

    
    private void judgeBankCard(String text, String path, List<String> reasons) {
        if (!text.matches("\\d{16,19}")) {
            return;
        }
        if (!GenKit.luhnValid(text)) {
            reasons.add("字段 " + path + " 银行卡号 Luhn 校验位不符（疑似模型编造假号，"
                    + "请改用 ${gen.bankCard(seed=数字)} 占位符）");
        }
    }

    
    private void judgeUscc(String text, String path, List<String> reasons) {
        if (!text.matches("[0-9A-HJ-NP-RTUWXY]{18}")) {
            return;
        }
        char expected;
        try {
            expected = GenKit.usccCheckChar(text.substring(0, 17));
        } catch (Exception e) {
            return;
        }
        if (expected != text.charAt(17)) {
            reasons.add("字段 " + path + " 统一社会信用代码校验码不符 GB32100（疑似模型编造假号，"
                    + "请改用 ${gen.uscc(seed=数字)} 占位符）");
        }
    }

    
    private void judgeIban(String text, String path, List<String> reasons) {
        if (!text.matches("DE\\d{2}[0-9A-Z]{18}")) {
            return;
        }
        if (!GenKit.ibanValid(text)) {
            reasons.add("字段 " + path + " IBAN mod97 校验不符（疑似模型编造假号，"
                    + "请改用 ${gen.iban(seed=数字)} 占位符）");
        }
    }
}
