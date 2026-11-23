package com.sixbean.testpike.core.plans.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Three-facet AND gate for launching a rehearsal batch from the conversation
 * : dialog execution master switch AND rehearsal-run
 * sub switch AND project whitelist, evaluated coarse to fine so the refusal
 * always names the FIRST closed facet.
 * <p>Pinned faces: ① every refusal sentence carries the literal config key of
 * the closed facet plus the literal settings path — the honest-guidance
 * contract (a switched-off feature never stays silent); ② the sub switch ships
 * OFF (production-change safety exemption of the default-open rule), while the
 * master switch keeps its own factory default; ③ an empty whitelist refuses
 * every project (fail-closed), and a whitelist without the asking project
 * refuses while naming that project id; ④ malformed CSV tokens are ignored
 * instead of poisoning the whole list, but a list of only garbage is empty =
 * refused; ⑤ the allow path returns exactly three declaration lines, one per
 * facet (declare-before-release contract), each naming its key; ⑥ values are
 * re-read per call, so a hot update flips the verdict immediately.
 * <p>ConfigService is stubbed here on purpose: this class pins the judgement
 * only; the key rows themselves land through the V336 seed and the live-DB
 * spectrum runs later on the door test.
 */
class RehearsalExecGateTest {

    private static final long PROJECT_ID = 7L;
    private static final String SETTINGS_PATH = "系统设置";

    private final ConfigService configService = mock(ConfigService.class);
    private final Map<String, String> values = new HashMap<>();
    private final RehearsalExecGate gate = buildGate();

    private RehearsalExecGate buildGate() {
        when(configService.getValue(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
        return new RehearsalExecGate(configService);
    }

    private String refuseMessage() {
        ServiceException e = assertThrows(ServiceException.class, () -> gate.requireAllowed(PROJECT_ID));
        return e.getMessage();
    }

    @Test
    void refusesNamingTheMasterSwitchWhenTheDialogExecutionFacetIsOff() {
        values.put("ai.chat.exec-enabled", "false");
        values.put("ai.chat.rehearsal-run.enabled", "true");
        values.put("ai.chat.exec-project-whitelist", "7");

        String message = refuseMessage();
        assertTrue(message.contains("ai.chat.exec-enabled"), message);
        assertTrue(message.contains(SETTINGS_PATH), message);
        assertFalse(message.contains("已启动"), message);
    }

    @Test
    void refusesNamingTheRehearsalSubSwitchByFactoryDefault() {
        // The sub switch key is deliberately left unset: the reader-side
        // factory default (off) is what this case pins.
        values.put("ai.chat.exec-project-whitelist", "7");

        String message = refuseMessage();
        assertTrue(message.contains("ai.chat.rehearsal-run.enabled"), message);
        assertTrue(message.contains(SETTINGS_PATH), message);
    }

    @Test
    void refusesNamingTheWhitelistKeyWhenTheListIsEmptyByDefault() {
        values.put("ai.chat.rehearsal-run.enabled", "true");

        String message = refuseMessage();
        assertTrue(message.contains("ai.chat.exec-project-whitelist"), message);
        assertTrue(message.contains(SETTINGS_PATH), message);
    }

    @Test
    void refusesAnAskingProjectThatTheWhitelistDoesNotList() {
        values.put("ai.chat.rehearsal-run.enabled", "true");
        values.put("ai.chat.exec-project-whitelist", "8,9");

        String message = refuseMessage();
        assertTrue(message.contains("ai.chat.exec-project-whitelist"), message);
        assertTrue(message.contains(String.valueOf(PROJECT_ID)), message);
    }

    @Test
    void ignoresMalformedWhitelistTokensButStillHitsALegalId() {
        values.put("ai.chat.rehearsal-run.enabled", "true");
        values.put("ai.chat.exec-project-whitelist", "abc, 7 ,!!");

        List<String> declarations = gate.requireAllowed(PROJECT_ID);
        assertEquals(3, declarations.size());
    }

    @Test
    void allowsWithOneDeclarationLinePerFacetWhenAllThreeAreOpen() {
        values.put("ai.chat.rehearsal-run.enabled", "true");
        values.put("ai.chat.exec-project-whitelist", "7");

        List<String> declarations = gate.requireAllowed(PROJECT_ID);
        assertEquals(3, declarations.size());
        assertTrue(declarations.get(0).contains("ai.chat.exec-enabled"), declarations.toString());
        assertTrue(declarations.get(1).contains("ai.chat.exec-project-whitelist"), declarations.toString());
        assertTrue(declarations.get(1).contains("7"), declarations.toString());
        assertTrue(declarations.get(2).contains("ai.chat.rehearsal-run.enabled"), declarations.toString());
    }

    @Test
    void hotUpdateFlipsTheVerdictWithoutRebuildingTheGate() {
        values.put("ai.chat.rehearsal-run.enabled", "true");
        values.put("ai.chat.exec-project-whitelist", "7");
        assertFalse(gate.requireAllowed(PROJECT_ID).isEmpty());

        values.put("ai.chat.rehearsal-run.enabled", "false");
        refuseMessage();
    }

    @Test
    void whitelistParsingIsFailClosedOnGarbageAndKeepsLegalIds() {
        assertTrue(RehearsalExecGate.parseWhitelist(null).isEmpty());
        assertTrue(RehearsalExecGate.parseWhitelist("").isEmpty());
        assertTrue(RehearsalExecGate.parseWhitelist("abc").isEmpty());
        assertEquals(Set.of(1L, 2L), RehearsalExecGate.parseWhitelist(" 1 , x, 2 "));
    }
}
