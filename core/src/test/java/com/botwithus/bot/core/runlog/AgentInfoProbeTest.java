package com.botwithus.bot.core.runlog;

import com.botwithus.bot.core.rpc.RpcRemoteException;
import com.botwithus.bot.core.rpc.RpcTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rpc.agent_info} against a fake agent standing at the boundary the probe
 * calls ({@code RpcClient.callSync}): it answers in full, with the error an older
 * agent sends, with a key missing, or too slowly.
 */
class AgentInfoProbeTest {

    private static final String SENTINEL = "deadb51d000000000000000000000001";
    private static final Duration DEADLINE = Duration.ofMillis(300);
    private static final long SLOW_REPLY_MS = 3_000;

    private static Map<String, Object> fullReply() {
        return Map.of("build_id", SENTINEL, "game_build", "950-1", "offsets_target", "950-1");
    }

    @Test
    void allKeysPresent_areWrittenThrough_sentinelIncluded() {
        AgentIdentity id = AgentInfoProbe.fetch(AgentInfoProbeTest::fullReply, DEADLINE);
        assertAll(
                () -> assertEquals(SENTINEL, id.agentBuild()),
                () -> assertEquals("950-1", id.gameRevision()));
    }

    @Test
    void anErrorReply_isUnknown_whateverItSays() {
        AgentIdentity older = AgentInfoProbe.fetch(() -> {
            throw new RpcRemoteException(AgentInfoProbe.METHOD, "method not found: rpc.agent_info");
        }, DEADLINE);
        AgentIdentity other = AgentInfoProbe.fetch(() -> {
            throw new RpcRemoteException(AgentInfoProbe.METHOD, "something else entirely");
        }, DEADLINE);
        AgentIdentity transport = AgentInfoProbe.fetch(() -> {
            throw new RpcTimeoutException(AgentInfoProbe.METHOD, 1);
        }, DEADLINE);
        assertAll(
                () -> assertEquals(AgentIdentity.UNKNOWN, older),
                () -> assertEquals(AgentIdentity.UNKNOWN, other),
                () -> assertEquals(AgentIdentity.UNKNOWN, transport));
    }

    @Test
    void aMissingOrBlankKey_isUnknown_andTheOtherIsKept() {
        AgentIdentity noGameBuild = AgentInfoProbe.fetch(() -> Map.of("build_id", SENTINEL), DEADLINE);
        AgentIdentity blankBuildId = AgentInfoProbe.fetch(
                () -> Map.of("build_id", "  ", "game_build", "950-1"), DEADLINE);
        AgentIdentity empty = AgentInfoProbe.fetch(Map::of, DEADLINE);
        assertAll(
                () -> assertEquals(new AgentIdentity(SENTINEL, "unknown"), noGameBuild),
                () -> assertEquals(new AgentIdentity("unknown", "950-1"), blankBuildId),
                () -> assertEquals(AgentIdentity.UNKNOWN, empty));
    }

    @Test
    void aReplyOverTheDeadline_isUnknown_andIsNotWaitedFor() {
        CountDownLatch release = new CountDownLatch(1);
        Instant before = Instant.now();
        AgentIdentity id = AgentInfoProbe.fetch(() -> {
            try {
                release.await(SLOW_REPLY_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fullReply();
        }, DEADLINE);
        long waitedMs = Duration.between(before, Instant.now()).toMillis();
        release.countDown();
        assertAll(
                () -> assertEquals(AgentIdentity.UNKNOWN, id),
                () -> assertTrue(waitedMs < SLOW_REPLY_MS, "waited " + waitedMs + " ms"));
    }

    @Test
    void start_isUnknownUntilTheAgentAnswers_thenTheAnswer() throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        AgentInfoProbe.Pending pending = AgentInfoProbe.start(() -> {
            try {
                answer.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fullReply();
        }, Duration.ofSeconds(5));
        AgentIdentity before = pending.get();
        answer.countDown();
        assertTrue(pending.awaitSettled(Duration.ofSeconds(5)));
        assertAll(
                () -> assertEquals(AgentIdentity.UNKNOWN, before),
                () -> assertEquals(new AgentIdentity(SENTINEL, "950-1"), pending.get()));
    }

    @Test
    void theHeader_keepsTheSentinel_throughRedaction() {
        AgentIdentity id = AgentInfoProbe.fetch(AgentInfoProbeTest::fullReply, DEADLINE);
        RunLogHeader header = new RunLogHeader("3f9c1a2be0d84c1e9a7f5d2b6c0e4a11", "v", 22,
                id.agentBuild(), id.gameRevision(), "S", "1", "a", "local", null, "os", "rt",
                Instant.parse("2026-10-06T00:00:00Z"), 1);
        List<String> lines = header.render(Redactor.withNames(KnownNames.NONE));
        assertAll(
                () -> assertTrue(lines.contains("agent_build: " + SENTINEL), lines.toString()),
                () -> assertTrue(lines.contains("game_revision: 950-1"), lines.toString()));
    }
}
