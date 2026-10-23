package com.botwithus.bot.core.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The vendored fixtures against the launcher repository itself, at the sha
 * {@code VENDOR.md} names: same files, same bytes. Opt-in
 * ({@code :core:launcherFixtureDriftTest}), because it needs a launcher
 * checkout beside this repo, which a clean clone and CI do not have.
 * {@link LauncherFixturesTest} holds the copy to its recorded blob ids on every build.
 */
class LauncherFixtureDriftTest {

    /** Where the launcher checkout is; the opt-in task sets it. */
    static final String REPO_PROPERTY = "botwithus.launcher.repo";
    private static final String FIXTURE_DIR = "protocol/fixtures/";

    @Test
    void vendoredCopy_isByteIdenticalToTheLauncherAtTheVendoredSha() throws Exception {
        String repoProperty = System.getProperty(REPO_PROPERTY);
        assumeTrue(repoProperty != null, REPO_PROPERTY + " is not set; not run");
        Path repo = Path.of(repoProperty);
        assumeTrue(Files.isDirectory(repo.resolve(".git")) || Files.isRegularFile(repo.resolve(".git")),
                "no launcher checkout at " + repo + "; not run");
        String sha = LauncherFixtures.SOURCE_SHA;
        JsonObject upstreamManifest = JsonParser.parseString(new String(
                git(repo, "show", sha + ":" + FIXTURE_DIR + "manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<JsonObject> upstreamAutomation = upstreamManifest.getAsJsonArray("fixtures").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(f -> f.getAsJsonArray("surface").contains(new JsonPrimitive("automation")))
                .toList();
        List<JsonObject> vendoredEntries = JsonParser.parseString(LauncherFixtures.read(
                LauncherFixtures.load().dir().resolve("fixtures/manifest.json"))).getAsJsonObject()
                .getAsJsonArray("fixtures").asList().stream().map(JsonElement::getAsJsonObject).toList();
        assertEquals(upstreamAutomation, vendoredEntries, "manifest entries vs the automation entries at " + sha);
        Path vendored = LauncherFixtures.load().dir().resolve("fixtures");
        for (JsonObject entry : upstreamAutomation) {
            String file = entry.get("file").getAsString();
            assertArrayEquals(git(repo, "show", sha + ":" + FIXTURE_DIR + file),
                    Files.readAllBytes(vendored.resolve(file)), file + " differs from " + sha);
        }
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
