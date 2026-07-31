package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.ScriptRevokedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptGateTest {

    @Test
    @DisplayName("an untagged host thread is never revoked")
    void hostThreadsAlwaysPass() {
        ScriptGate gate = new ScriptGate();
        gate.revoke("Miner");

        assertFalse(gate.isCallerRevoked());
        assertDoesNotThrow(gate::checkCaller);
    }

    @Test
    @DisplayName("a tagged thread passes until its script is revoked")
    void taggedThreadIsGatedOnRevocation() {
        ScriptGate gate = new ScriptGate();
        gate.enter("Miner");
        assertDoesNotThrow(gate::checkCaller);

        gate.revoke("Miner");

        ScriptRevokedException thrown = assertThrows(ScriptRevokedException.class, gate::checkCaller);
        assertEquals("Miner", thrown.scriptName());
    }

    @Test
    @DisplayName("revoking one script does not gate another")
    void revocationIsPerScript() {
        ScriptGate gate = new ScriptGate();
        gate.enter("Woodcutter");
        gate.revoke("Miner");

        assertDoesNotThrow(gate::checkCaller);
    }

    @Test
    @DisplayName("threads a script spawns inherit its tag")
    void spawnedThreadsInheritTheTag() throws Exception {
        // This is what makes the walk executor tractable: ww-executor is started
        // from the script's own thread inside GameAPIImpl.walkWorldPathAsync, so
        // it inherits the tag and the same revocation reaches it. Without this,
        // a stopped script's walk keeps queueing actions.
        ScriptGate gate = new ScriptGate();
        AtomicReference<String> seenByChild = new AtomicReference<>();
        AtomicReference<Boolean> childRevoked = new AtomicReference<>();

        Thread scriptThread = new Thread(() -> {
            gate.enter("Miner");
            Thread spawned = new Thread(() -> {
                seenByChild.set(gate.current());
                gate.revoke("Miner");
                childRevoked.set(gate.isCallerRevoked());
            });
            spawned.start();
            try {
                spawned.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        scriptThread.start();
        scriptThread.join(5000);

        assertEquals("Miner", seenByChild.get(), "spawned thread should inherit the script tag");
        assertTrue(childRevoked.get(), "revocation should reach the spawned thread too");
    }

    @Test
    @DisplayName("exit clears the calling thread's tag")
    void exitClearsTheTag() {
        ScriptGate gate = new ScriptGate();
        gate.enter("Miner");
        gate.exit();
        gate.revoke("Miner");

        assertFalse(gate.isCallerRevoked());
    }
}
