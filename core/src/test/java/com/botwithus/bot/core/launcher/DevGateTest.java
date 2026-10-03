package com.botwithus.bot.core.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The development override gate. The override is honoured only with the
 * opt-in property and outside a jpackage launcher: the four combinations, and
 * the launcher's own validation of the value.
 */
class DevGateTest {

    private static final UnaryOperator<String> SCOPE_ENV = env(DevGate.SCOPE_VARIABLE, "a6test");

    @ParameterizedTest(name = "optedIn={0} packaged={1} -> enabled={2}")
    @CsvSource({
            "false, false, false",
            "true,  false, true",
            "false, true,  false",
            "true,  true,  false",
    })
    void gate_needsTheOptInAndNoJpackage(boolean isOptedIn, boolean isPackaged, boolean isEnabled) {
        Map<String, String> props = new HashMap<>();
        if (isOptedIn) {
            props.put(DevGate.PROPERTY, "true");
        }
        if (isPackaged) {
            props.put(DevGate.JPACKAGE_PROPERTY, "BotWithUs.exe");
        }
        DevGate gate = DevGate.from(props::get);
        assertEquals(isEnabled, gate.isEnabled());
        assertEquals(isEnabled ? Optional.of("deva6test") : Optional.empty(), gate.scopeOverride(SCOPE_ENV));
    }

    @Test
    void withoutTheProperty_theVariableIsIgnored() {
        DevGate gate = DevGate.from(name -> null);
        assertEquals(Optional.empty(), gate.scopeOverride(SCOPE_ENV));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "dash-ed", "under_score", "123456789012345678901234567890123", "é"})
    void invalidValues_areIgnored(String value) {
        DevGate gate = new DevGate(true);
        assertEquals(Optional.empty(), gate.scopeOverride(env(DevGate.SCOPE_VARIABLE, value)));
    }

    @Test
    void thirtyTwoAlphanumerics_isTheLongestAccepted() {
        String value = "abcdefghijABCDEFGHIJ0123456789xy";
        assertEquals(Optional.of("dev" + value), new DevGate(true).scopeOverride(env(DevGate.SCOPE_VARIABLE, value)));
    }

    @Test
    void closeAnswer_onlyThroughTheGate() {
        UnaryOperator<String> props = env(DevGate.CLOSE_ANSWER_PROPERTY, "Declined");
        assertEquals(Optional.of(CloseDecision.DECLINED), new DevGate(true).closeRequestAnswer(props));
        assertEquals(Optional.empty(), new DevGate(false).closeRequestAnswer(props));
        assertEquals(Optional.empty(), new DevGate(true).closeRequestAnswer(env(DevGate.CLOSE_ANSWER_PROPERTY, "x")));
    }

    private static UnaryOperator<String> env(String name, String value) {
        return key -> key.equals(name) ? value : null;
    }
}
