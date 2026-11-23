package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockPruneResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AppautoWxMockGenKitGateTest {

    private final AppautoWxMockGenKitGate gate = new AppautoWxMockGenKitGate();

    private static Map<String, Object> mock(String scenario, Map<String, Object> response) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario);
        row.put("response", response);
        return row;
    }

    private static String flipIdCard(String real) {
        return real.substring(0, 17) + (real.charAt(17) == '1' ? '2' : '1');
    }

    @Test
    void keepsGenKitValidIdsUntouched() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idCard", GenKit.idCard(7L));
        response.put("bankCard", GenKit.bankCard(8L));
        response.put("uscc", GenKit.uscc(9L));
        AppautoWxMockPruneResult result = gate.prune(List.of(mock("normal", response)));
        assertEquals(1, result.kept().size(), "GenKit 真校验位号不应被剪");
        assertEquals(0, result.pruned());
        @SuppressWarnings("unchecked")
        Map<String, Object> keptResponse =
                (Map<String, Object>) result.kept().get(0).get("response");
        assertEquals(GenKit.idCard(7L), keptResponse.get("idCard"));
        assertEquals(GenKit.bankCard(8L), keptResponse.get("bankCard"));
        assertEquals(GenKit.uscc(9L), keptResponse.get("uscc"));
    }

    @Test
    void prunesWrongCheckDigitReasonNamesTypeAndPath() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idCard", flipIdCard(GenKit.idCard(7L)));
        AppautoWxMockPruneResult result = gate.prune(List.of(mock("normal", response)));
        assertEquals(0, result.kept().size());
        assertEquals(1, result.pruned());
        String reason = String.join("；", result.reasons());
        assertTrue(reason.contains("身份证"), "原因须点名号型：" + reason);
        assertTrue(reason.contains("response.idCard"), "原因须点名字段路径：" + reason);
    }

    @Test
    void ignoresGenPlaceholderStrings() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idCard", "${gen.idCard(seed=123)}");
        response.put("bankCard", "${gen.bankCard(seed=456)}");
        AppautoWxMockPruneResult result = gate.prune(List.of(mock("normal", response)));
        assertEquals(1, result.kept().size(), "${gen.*} 占位符归 env 链展开复校，本闸不判");
        assertEquals(0, result.pruned());
    }

    @Test
    void descendsNestedMapAndListWithPathNaming() {
        String fake = flipIdCard(GenKit.idCard(7L));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("idCard", fake);
        Map<String, Object> listHost = new LinkedHashMap<>();
        listHost.put("rows", List.of(inner));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", listHost);
        AppautoWxMockPruneResult result = gate.prune(List.of(mock("boundary", response)));
        assertEquals(0, result.kept().size());
        assertTrue(String.join("；", result.reasons()).contains("response.data.rows[0].idCard"),
                "嵌套路径点名须含 list 下标：" + result.reasons());
    }

    @Test
    void shapeMismatchAndNumericValuesAreNotJudged() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("amount", 440305198812063725L);
        response.put("idCard", "");
        response.put("bankCard", "62220212345678901234567");
        assertEquals(1, gate.prune(List.of(mock("boundary", response))).kept().size(),
                "数字型/形状不匹配值（空串/超长=boundary 合法取值）无校验和语义，禁误剪");
    }

    @Test
    void prunesOnlyFakeRowsKeepingValidOnes() {
        String bank = GenKit.bankCard(3L);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("bankCard", bank.substring(0, 17) + (bank.charAt(17) == '1' ? '2' : '1'));
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("bankCard", GenKit.bankCard(4L));
        AppautoWxMockPruneResult result = gate.prune(
                List.of(mock("normal", good), mock("boundary", bad)));
        assertEquals(1, result.kept().size(), "混合面=剪假留真");
        assertEquals(1, result.pruned());
        assertEquals("normal", result.kept().get(0).get("scenario"));
        assertTrue(String.join("；", result.reasons()).contains("银行卡"));
    }

    @Test
    void judgesOnlyDeIbanLayout() {
        Map<String, Object> fr = new LinkedHashMap<>();
        fr.put("iban", "FR123456789012345678901234567");
        assertEquals(1, gate.prune(List.of(mock("normal", fr))).kept().size(),
                "非 DE 版式 IBAN 无自建生成器基准，禁假阳剪除");
        Map<String, Object> badDe = new LinkedHashMap<>();
        badDe.put("iban", "DE89370400440532013001");
        AppautoWxMockPruneResult result = gate.prune(List.of(mock("boundary", badDe)));
        assertEquals(0, result.kept().size(), "DE 版式 mod97 错=假号剪除");
        assertTrue(String.join("；", result.reasons()).contains("IBAN"));
    }

    @Test
    void registryRandomFamilyIsTheDownstreamManifestSource() {
        List<String> randomFamily = new ArrayList<>();
        for (GenFunction f : GenFunction.values()) {
            if (f.isRandom()) {
                randomFamily.add(f.functionName());
            }
        }
        assertTrue(randomFamily.containsAll(List.of("idCard", "bankCard", "uscc", "iban")),
                "四号族必在随机族清单（复校闸与下传清单同源）");
        assertEquals(randomFamily, gate.randomFamilyManifest(),
                "下传清单=注册表随机族保序单源，禁手抄（红线 6）");
    }
}
