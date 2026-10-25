package com.botwithus.bot.core.shm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Layout} against the producer's own {@code SharedLayout.h}, read out of an NXTLibrary
 * checkout at a pinned revision. {@link LayoutWireOffsetsTest} pins literals typed into this repo;
 * this test pins them to the header those literals were copied from, so a literal that was copied
 * wrong, or a header that moved on, fails here.
 *
 * <p>Opt-in ({@code :core:producerHeaderDriftTest}) because it needs the private producer
 * checkout, which a clean clone and CI do not have:</p>
 * <pre>
 * ./gradlew :core:producerHeaderDriftTest -Pbotwithus.producer.repo=&lt;NXTLibrary checkout&gt;
 *         [-Pbotwithus.producer.rev=&lt;sha&gt;]
 * </pre>
 *
 * <p>Every entry in {@link #expectations()} must be stated by the header, as a
 * {@code static_assert(offsetof(T, f) == N)}, a {@code static_assert(sizeof(T) == N)} or a
 * {@code constexpr} constant. A header that no longer states one is a failure, not a pass: an
 * older or newer layout that drops a pin must not certify this host.</p>
 */
class ProducerHeaderDriftTest {

    /** Where the NXTLibrary checkout is; the opt-in task sets it. */
    static final String REPO_PROPERTY = "botwithus.producer.repo";
    /** Which producer revision to read; defaults to {@link #BOUND_PRODUCER_SHA}. */
    static final String REV_PROPERTY = "botwithus.producer.rev";
    /** The producer revision this host's {@link Layout} is bound to (NXTLibrary PR #41 head; c1231ca..3c9ba5a changed no wire file). */
    static final String BOUND_PRODUCER_SHA = "3c9ba5af93c3eb54684c79da6b17a1c85bfffd3a";
    private static final String HEADER_PATH = "src/ipc/SharedLayout.h";

    // The literal must END the comparison (followed by ',', ')' or '&&'). Many asserts compare
    // against an expression such as "32 + sizeof(LocalPlayer)", whose leading number is not the
    // offset; only an absolute literal is a value this test can check.
    private static final Pattern ASSERT = Pattern.compile(
            "static_assert\\s*\\(\\s*(offsetof|sizeof)\\s*\\(\\s*(\\w+)\\s*(?:,\\s*(\\w+)\\s*)?\\)"
                    + "\\s*==\\s*(\\d+)\\s*(?=,|\\)|&&)");
    private static final Pattern CONSTANT = Pattern.compile(
            "constexpr\\s+\\w+\\s+(k\\w+)\\s*=\\s*(\\d+)u?\\s*(?:<<\\s*(\\d+))?\\s*;");

    @Test
    void layoutAgreesWithTheProducerHeader() throws Exception {
        String header = readHeader();
        Map<String, Long> stated = statedValues(header);
        List<String> problems = new ArrayList<>();
        expectations().forEach((key, expected) -> {
            Long actual = stated.get(key);
            if (actual == null) {
                problems.add(key + ": not stated by the header (host expects " + expected + ")");
            } else if (actual != expected.longValue()) {
                problems.add(key + ": header says " + actual + ", Layout says " + expected);
            }
        });
        assertTrue(problems.isEmpty(), "Layout disagrees with " + HEADER_PATH + " at "
                + revision() + ":\n  " + String.join("\n  ", problems));
    }

    /** What {@link Layout} claims, keyed the way {@link #statedValues} keys the header. */
    private static Map<String, Integer> expectations() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("kProtocolVersion", Layout.PROTOCOL_VERSION);
        m.put("kPlayerNameCap", Layout.PLAYER_NAME_CAP);
        m.put("kFlagNameTruncated", Layout.FLAG_NAME_TRUNCATED);
        m.put("kOpenIfaceCap", Layout.OPEN_IFACE_CAP);
        m.put("kDynChunkCap", Layout.DYN_CHUNK_CAP);
        m.put("sizeof(Snapshot)", Layout.SNAPSHOT_SIZE);
        m.put("sizeof(NpcEntry)", Layout.NPC_ENTRY_SIZE);
        m.put("sizeof(PlayerEntry)", Layout.PLAYER_ENTRY_SIZE);
        m.put("sizeof(LocalPlayer)", Layout.LOCAL_PLAYER_SIZE);
        m.put("offsetof(NpcEntry,activeTypeId)", Layout.NPC_ACTIVETYPEID_OFFSET);
        m.put("offsetof(NpcEntry,combatLevel)", Layout.NPC_COMBATLEVEL_OFFSET);
        m.put("offsetof(PlayerEntry,name)", Layout.PLAYER_NAME_OFFSET);
        m.put("offsetof(LocalPlayer,runEnergy)", Layout.LP_RUNENERGY_OFFSET);
        m.put("offsetof(LocalPlayer,runWeight)", Layout.LP_RUNWEIGHT_OFFSET);
        m.put("offsetof(Snapshot,players)", Layout.SNAP_PLAYERS_OFFSET);
        m.put("offsetof(Snapshot,openIfaceCount)", Layout.SNAP_OPENIFACECOUNT_OFFSET);
        m.put("offsetof(Snapshot,openIfaceTotal)", Layout.SNAP_OPENIFACETOTAL_OFFSET);
        m.put("offsetof(Snapshot,openIfaces)", Layout.SNAP_OPENIFACES_OFFSET);
        m.put("offsetof(Snapshot,openIfaceFlags)", Layout.SNAP_OPENIFACEFLAGS_OFFSET);
        m.put("offsetof(Snapshot,groundItemCount)", Layout.SNAP_GROUNDITEMCOUNT_OFFSET);
        m.put("offsetof(Snapshot,dynRegion)", Layout.SNAP_DYNREGION_OFFSET);
        return m;
    }

    /**
     * Every literal the header states: {@code offsetof(T,f)} and {@code sizeof(T)} from its
     * {@code static_assert}s, and {@code kName} from its integer {@code constexpr}s. When the same
     * key is asserted twice with different values, the header would not compile, so the first
     * one wins without loss.
     */
    private static Map<String, Long> statedValues(String header) {
        Map<String, Long> out = new LinkedHashMap<>();
        Matcher a = ASSERT.matcher(header);
        while (a.find()) {
            String key = a.group(3) == null
                    ? a.group(1) + "(" + a.group(2) + ")"
                    : a.group(1) + "(" + a.group(2) + "," + a.group(3) + ")";
            out.putIfAbsent(key, Long.parseLong(a.group(4)));
        }
        Matcher c = CONSTANT.matcher(header);
        while (c.find()) {
            long value = Long.parseLong(c.group(2));
            if (c.group(3) != null) {
                value <<= Integer.parseInt(c.group(3));
            }
            out.putIfAbsent(c.group(1), value);
        }
        return out;
    }

    private static String readHeader() throws IOException, InterruptedException {
        String repoProperty = System.getProperty(REPO_PROPERTY);
        assumeTrue(repoProperty != null, REPO_PROPERTY + " is not set; not run");
        Path repo = Path.of(repoProperty);
        assumeTrue(Files.exists(repo.resolve(".git")), "no producer checkout at " + repo + "; not run");
        return new String(git(repo, "show", revision() + ":" + HEADER_PATH), StandardCharsets.UTF_8);
    }

    private static String revision() {
        return System.getProperty(REV_PROPERTY, BOUND_PRODUCER_SHA);
    }

    private static byte[] git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        byte[] out;
        try (InputStream in = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            out = buffer.toByteArray();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git " + String.join(" ", args) + " exited " + exit);
        }
        return out;
    }
}
