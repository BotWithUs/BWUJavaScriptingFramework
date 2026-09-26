import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    application
    alias(libs.plugins.beryx.jlink)
    alias(libs.plugins.gradlex.extra.java.module.info)
}

// LWJGL ships per-platform natives as classifier artifacts (foo:bar:VERSION:natives-windows).
// Version-catalog entries cannot carry a classifier, so we resolve the version
// from the catalog and apply the classifier here at the single use site.
val lwjglVersion = libs.versions.lwjgl.get()
val lwjglNatives = "natives-windows"

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(libs.gson)

    // imgui-java-app is the "runs anywhere" convenience artifact: its POM pulls
    // LWJGL's natives for *every* platform, which is how 9 undeclared
    // natives-linux / natives-macos / natives-macos-arm64 jars were reaching a
    // Windows-only product's runtime classpath and its shipped runtime image.
    // We want its classes (imgui.app.Application, which ImGuiApp extends), not
    // its platform set, so LWJGL is excluded here and declared deliberately
    // below: the base artifacts for the classes, one classifier for the natives.
    implementation(libs.imgui.java.app) {
        exclude(group = "org.lwjgl")
    }
    implementation(libs.bundles.lwjgl.base)

    runtimeOnly(libs.imgui.java.natives.windows)
    runtimeOnly("${libs.lwjgl.core.get().module}:$lwjglVersion:$lwjglNatives")
    runtimeOnly("${libs.lwjgl.glfw.get().module}:$lwjglVersion:$lwjglNatives")
    runtimeOnly("${libs.lwjgl.opengl.get().module}:$lwjglVersion:$lwjglNatives")
    implementation(libs.logback.classic)
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
}

application {
    mainClass = "com.botwithus.bot.cli.gui.ImGuiApp"
    mainModule = "com.botwithus.bot.cli"
}

// Converts cli/src/main/resources/icon.png to a Windows multi-resolution
// icon.ico (16/32/48/64/128/256 px). Skipped when icon.png is absent, so
// the build keeps working before the user drops in a logo. Uses Pillow
// because it's the only cross-shell tool installed everywhere we build —
// rewriting an ICO container in Java would be more code than it's worth
// for a build-time conversion.
val iconSource = layout.projectDirectory.file("src/main/resources/icon.png")
val iconOutput = layout.projectDirectory.file("src/main/resources/icon.ico")

val convertIcon by tasks.registering(Exec::class) {
    onlyIf { iconSource.asFile.exists() }
    inputs.file(iconSource).optional()
    outputs.file(iconOutput)
    executable = "python"
    args(
        "-c",
        """
        from PIL import Image
        img = Image.open(r'${iconSource.asFile.absolutePath}')
        img.save(
            r'${iconOutput.asFile.absolutePath}',
            format='ICO',
            sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)],
        )
        """.trimIndent(),
    )
}

val extractNatives by tasks.registering(Copy::class) {
    val nativeJars = configurations.runtimeClasspath.get().filter { it.name.contains("natives") }
    nativeJars.forEach { from(zipTree(it)) }
    into(layout.buildDirectory.dir("natives"))
    include("**/*.dll", "**/*.so", "**/*.dylib")
}

tasks.named<JavaExec>("run") {
    dependsOn(extractNatives)
    workingDir = rootProject.projectDir
    jvmArgs(
        "-Dorg.lwjgl.librarypath=${layout.buildDirectory.dir("natives").get().asFile.absolutePath}",
        // FFM downcalls in com.botwithus.bot.core.cache.NXTCache hit the
        // restricted Linker API; J22+ requires explicit native-access opt-in.
        // sqlite-jdbc (org.xerial.sqlitejdbc) System.load()s its native lib when
        // a skilling script opens the Atlas — opt it in too so the read is clean.
        "--enable-native-access=com.botwithus.bot.core,org.xerial.sqlitejdbc",
    )
    // Optional: point at the NXTCache DLL + cache directory to enable
    // cache-backed config-type lookups (item/npc/loc/quest/etc.). Set these
    // in local.properties, pass them on the command line
    // (-Pnxtcache.dll=... -Pnxtcache.path=...), or export NXTCACHE_DLL /
    // NXTCACHE_PATH.
    project.localProperty("nxtcache.dll", "NXTCACHE_DLL")
        ?.let { jvmArgs("-Dnxtcache.dll=$it") }
    project.localProperty("nxtcache.path", "NXTCACHE_PATH")
        ?.let { jvmArgs("-Dnxtcache.path=$it") }
    // Optional: dev override for the WorldWalker DLL + baked artifact, read by
    // core.worldwalker.WorldWalker via NativeCache.locateWorldWalkerDll() /
    // locateWorldWalkerArtifact(). When unset, both fall back to
    // ~/.botwithus/native/.
    project.localProperty("worldwalker.dll", "WORLDWALKER_DLL")
        ?.let { jvmArgs("-Dworldwalker.dll=$it") }
    project.localProperty("worldwalker.artifact", "WORLDWALKER_ARTIFACT")
        ?.let { jvmArgs("-Dworldwalker.artifact=$it") }
    // Optional: dev override for the baked Atlas (resolved.sqlite), read by
    // skilling-core's AtlasPaths. When unset, skilling scripts fall back to
    // ~/.botwithus/native/resolved.sqlite.
    project.localProperty("botwithus.atlas", "BOTWITHUS_ATLAS")
        ?.let { jvmArgs("-Dbotwithus.atlas=$it") }
    // Optional: dev override for the baked gameval name index (gameval.sqlite),
    // read by core.gameval.SqliteGamevalIndex via NativeCache.locateGamevalDb().
    // When unset, falls back to ~/.botwithus/native/gameval.sqlite; when that is
    // absent too, gameval lookups resolve to nothing.
    project.localProperty("botwithus.gameval", "BOTWITHUS_GAMEVAL")
        ?.let { jvmArgs("-Dbotwithus.gameval=$it") }
}

// ── Dev-only Normal-mode preview ─────────────────────────────────────────────
// Renders the Normal-mode UI with fixture data (every card state, 6 and 12
// clients, empty, host offline, picker, inspector, toasts) and writes one PNG
// per scenario to build/preview/. It is how a UI change is checked without a
// game client or a person clicking through it.
//
// Gated by construction: the code lives in its own `preview` source set, which
// the `jar` task, the jlink image and the installer never read, so none of it can
// reach a user. It runs on the classpath (no module-info), which is also why it
// can reach the handful of package-private seams it needs in gui.usermode.
val preview: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("renderNormalModePreviews") {
    description = "Dev only: renders Normal mode from fixtures and writes one PNG per scenario to build/preview"
    group = "verification"
    dependsOn(extractNatives)
    classpath = preview.runtimeClasspath
    mainClass = "com.botwithus.bot.cli.gui.preview.NormalModePreview"
    val outDir = layout.buildDirectory.dir("preview")
    args(outDir.get().asFile.absolutePath)
    jvmArgs("-Dorg.lwjgl.librarypath=${layout.buildDirectory.dir("natives").get().asFile.absolutePath}")
    outputs.upToDateWhen { false }
}

// Resolve the JDK that the project's Java toolchain points at. beryx-jlink
// needs an explicit JDK path for both jlink and jpackage; under Gradle 9.5
// + an auto-provisioned toolchain it cannot discover one on its own and the
// jpackageImage task NPEs in JPackageData.getDefaultJPackageHome.
val toolchainJdkPath = javaToolchains
    .launcherFor(java.toolchain)
    .map { it.metadata.installationPath.asFile }

// Home of the patched runtime image. Shared with core's sdnValidationTest so a
// single setting drives both testing and packaging.
//
// NOT required to build. SdnLoader resolves jdk.internal.sdn.SdnClassLoader by
// name and gates every call on isAvailable(), so a stock JDK 25 compiles the
// host and runs local scripts unchanged. It IS required for the runtime image
// we ship, because the loader depends on runtime support a module cannot add.
// See verifySdnRuntime.
val sdnJdkPath = project.localProperty("sdn.jdk", "SDN_JDK")

jlink {
    // Precedence: an explicit jlink.javaHome wins, then the patched SDN JDK,
    // then the toolchain. Falling through to the toolchain is the supported
    // local-dev path — it just yields an image that cannot load SDN scripts.
    val jlinkHomeOverride = project.localProperty("jlink.javaHome", "JLINK_JAVA_HOME")
        ?: sdnJdkPath
    if (jlinkHomeOverride != null) {
        javaHome.set(file(jlinkHomeOverride))
    } else {
        javaHome.set(toolchainJdkPath.get())
    }
    options.set(listOf(
        "--strip-debug",
        "--compress", "zip-6",
        "--no-header-files",
        "--no-man-pages",
        // BouncyCastle ships signed modular JARs (bcprov-jdk18on). jlink
        // refuses to link signed modular JARs by default; we suppress the
        // check because the signature is irrelevant once the module is
        // baked into a custom runtime image.
        "--ignore-signing-information",
    ))
    launcher {
        name = "jbot"
        jvmArgs = listOf(
            // FFM downcalls in com.botwithus.bot.core (NXTCache, Kernel32 shm
            // bindings, WorldWalker) hit the restricted Linker API. Future
            // JDKs will refuse without opt-in.
            //
            // forceMerge("lwjgl") relocates LWJGL into the synthetic
            // com.botwithus.merged.module — LWJGL's System.load() trips the
            // same restriction from there, so the merged module is on the
            // list too.
            //
            // sqlite-jdbc ships a real module-info, so it links as itself
            // rather than being merged; it System.load()s its own native lib
            // when core opens the gameval index or a skilling script opens the
            // Atlas, so it needs its own entry here (the `run` task already
            // has one).
            "--enable-native-access=com.botwithus.bot.core,com.botwithus.merged.module,"
                    + "org.xerial.sqlitejdbc",
            // SdnDiskBundleSource.isEnabled() reads this and skips the whole
            // disk-delivery path when it is absent — silently, so an unset flag
            // presents as "the launcher's courier isn't running" (a 30s timeout)
            // rather than as a misconfiguration. The shipped host must always
            // carry it, and a jpackage launcher takes JVM options only from its
            // baked .cfg, so link time is the only place it can be set.
            //
            // The matching directory needs no flag: SdnRendezvous.directory()
            // and the launcher's matching lookup both default to
            // ~/.botwithus/sdn, so they agree unless one is overridden.
            "-Dbotwithus.sdn.disk=true",
        )
    }
    forceMerge("lwjgl")
    mergedModule {
        additive = true
    }

    // jpackage:
    //  - :cli:jpackageImage produces the unzip-and-run folder
    //    (build/jpackage/BotWithUs/BotWithUs.exe).
    //  - :cli:jpackage produces the MSI installer
    //    (build/jpackage/BotWithUs-<version>.msi) with proper Windows
    //    Installer upgrade semantics: the same --win-upgrade-uuid across
    //    versions lets a newer MSI cleanly replace an older install.
    //
    // Runtime data (scripts/, imgui.ini, config, native cache, logs) lives
    // under ~/.botwithus, so upgrades preserve user state and the install
    // folder stays read-only-safe.
    jpackage {
        // Same toolchain JDK as jlink. Without this the plugin NPEs trying
        // to discover a default jpackage home under Gradle 9.5 toolchains.
        jpackageHome = toolchainJdkPath.get().absolutePath
        imageName = "BotWithUs"
        // MSI ships as BotWithUs-<version>.msi (otherwise it inherits the
        // jlink launcher name "jbot" and would be jbot-<version>.msi).
        installerName = "BotWithUs"
        // jpackage rejects Maven-style "1.0-SNAPSHOT" — --app-version must
        // match MAJOR[.MINOR[.PATCH]]. Strip the qualifier for the bundle.
        // BUMP THIS FOR EACH RELEASE (root project.version). Windows
        // Installer requires a higher --app-version for upgrades to be
        // accepted; same version → "already installed", lower → blocked.
        appVersion = (project.version as String).substringBefore("-")
        skipInstaller = false
        installerType = "msi"

        // Build the option lists with --icon appended only when the
        // generated icon exists. jpackage rejects --icon pointing at a
        // missing file, so we keep the build green before the user
        // supplies icon.png.
        val iconArgs = if (iconOutput.asFile.exists()) {
            listOf("--icon", iconOutput.asFile.absolutePath)
        } else emptyList()

        imageOptions = listOf(
            // Keep stdout/stderr attached so logback's CONSOLE appender
            // stays visible. Without this jpackage produces a windowed
            // launcher and detaches the console.
            "--win-console",
        ) + iconArgs
        installerOptions = listOf(
            "--vendor", "BotWithUs",
            "--description", "BotWithUs script manager",
            "--copyright", "BotWithUs",
            // Stable UpgradeCode. NEVER change this once a public MSI has
            // shipped — Windows Installer keys upgrades off this GUID. A
            // change here makes the next MSI install side-by-side instead
            // of replacing the previous version.
            "--win-upgrade-uuid", "1FA442FE-6EFA-41A4-84A8-32FB1DD64041",
            // Per-user install: %LOCALAPPDATA%\BotWithUs, no UAC prompt,
            // no admin rights required. Matches the per-user data layout
            // under ~/.botwithus.
            "--win-per-user-install",
            // User can pick the install dir at install time.
            "--win-dir-chooser",
            // Start menu shortcut under "BotWithUs" group + desktop icon.
            "--win-menu",
            "--win-menu-group", "BotWithUs",
            "--win-shortcut",
        ) + iconArgs
    }
}

// Marker that identifies a shipped runtime as the patched one, as lowercase hex.
// Release builds set it; its value is configuration, not source, and lives with
// the runtime's own build rather than here.
//
// An image lacking the marker is either not the patched runtime, or was built
// against different material. Both fail the same way: local scripts keep working
// while every SDN script silently fails to load, so it reads as "SDN is broken"
// rather than "wrong runtime". Inspecting the shipped image catches both, which
// is why this checks the artifact rather than the path it was copied from.
val sdnRuntimeMarkerHex = project.localProperty("sdn.marker", "SDN_MARKER")

fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                continue@outer
            }
        }
        return true
    }
    return false
}

// Reports whether the linked image can actually load SDN scripts. An
// unconfigured local build gets a warning and stays green, so the host still
// builds and runs on a stock JDK 25; a build that configured sdn.jdk is by
// definition producing a shippable image, so there it fails hard rather than
// letting an unverifiable one through.
val verifySdnRuntime by tasks.registering {
    description = "Checks the linked image is the patched SDN-capable runtime"
    group = "verification"
    dependsOn(tasks.named("jlink"))

    val javaDll = layout.buildDirectory.file("image/bin/java.dll")
    val configuredFork = sdnJdkPath

    // Blank is not a value. localProperty null-checks but does not blank-check,
    // so a bare `sdn.marker=` yields "" rather than null — and "" would clear
    // the null branch below, parse to a zero-length needle, and match on
    // containsSequence's first iteration. The task would then report success on
    // an image it never verified, which is worse than the failure it exists to
    // catch. Normalised here rather than in localProperty: that helper is shared
    // with sdn.jdk and other callers, and is not ours to tighten globally.
    val markerHex = sdnRuntimeMarkerHex?.trim()?.takeIf { it.isNotEmpty() }

    inputs.file(javaDll)
    outputs.upToDateWhen { false }

    doLast {
        val dll = javaDll.get().asFile

        // No marker means the check cannot be performed at all. Say so in those
        // words: a guard that reports nothing looks exactly like a guard that
        // passed, which is the failure this task exists to prevent.
        if (markerHex == null) {
            val message = "verifySdnRuntime: SKIPPED — no sdn.marker set, so the image was NOT " +
                    "verified. Set sdn.marker in local.properties (or the SDN_MARKER env var)."
            if (configuredFork != null) {
                throw GradleException(
                    "$message sdn.jdk points at $configuredFork, so this build is producing an " +
                            "image intended for shipping and must not skip the check."
                )
            }
            logger.warn(message)
            return@doLast
        }

        // A marker short enough to occur by chance proves nothing: "00" is in
        // every binary, so a two-character value would pass this task on any
        // image at all. 16 bytes is the floor for a match to carry information.
        // Malformed input is rejected here too, so a typo surfaces as a build
        // error that names the problem rather than as a NumberFormatException
        // thrown out of toInt(16) below.
        val isWellFormedMarker = markerHex.length >= 32
                && markerHex.length % 2 == 0
                && markerHex.all { it in '0'..'9' || it in 'a'..'f' }
        if (!isWellFormedMarker) {
            throw GradleException(
                "verifySdnRuntime: sdn.marker is not usable. Expected an even number of " +
                        "lowercase hex characters, at least 32 of them (16 bytes); got " +
                        "${markerHex.length}. A short or malformed marker either fails to " +
                        "parse or matches any binary by chance, which would report success " +
                        "on an image that was never verified."
            )
        }

        val marker = markerHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val isSdnCapable = dll.isFile && dll.readBytes().containsSequence(marker)
        if (isSdnCapable) {
            logger.lifecycle("verifySdnRuntime: image IS SDN-capable — marker present in ${dll.name}")
        } else if (configuredFork != null) {
            throw GradleException(
                "verifySdnRuntime: sdn.jdk points at $configuredFork, but the linked image's " +
                        "${dll.name} does not carry the expected marker. Shipping it would " +
                        "silently break every SDN script. Rebuild the patched runtime as a " +
                        "release/PRODUCT image and re-link."
            )
        } else {
            logger.warn(
                "verifySdnRuntime: image is NOT SDN-capable — linked against the Gradle toolchain, " +
                        "not the patched runtime. Local scripts work; every SDN script will fail to " +
                        "load. Set sdn.jdk in local.properties (or the SDN_JDK env var) to produce " +
                        "a shippable image."
            )
        }
    }
}

val packageJre by tasks.registering(Zip::class) {
    dependsOn(tasks.named("jlink"), verifySdnRuntime)
    archiveFileName.set("jre.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("image"))
    val navDataDir = project.localProperty("navDataDir", "NAV_DATA_DIR")
    if (navDataDir != null) {
        from(navDataDir) {
            into("nav_data")
        }
    }
}

// Zips the jpackage app-image (build/jpackage/BotWithUs) for distribution.
// Produces build/distributions/BotWithUs-app.zip — unzip anywhere and run
// BotWithUs/BotWithUs.exe; no installer or admin required.
val packageApp by tasks.registering(Zip::class) {
    dependsOn(tasks.named("jpackageImage"))
    archiveFileName.set("BotWithUs-app.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("jpackage")) {
        // Exclude the MSI itself — packageApp is the unzip-and-run variant.
        exclude("*.msi")
    }
}

// Convert icon.png → icon.ico before jpackage runs, so --icon (added
// conditionally above) actually has a target on first build. Once the
// .ico exists it's reused unchanged across builds.
tasks.named("jpackageImage").configure { dependsOn(convertIcon) }
tasks.named("jpackage").configure { dependsOn(convertIcon) }

// jpackage hard-codes the filename as "<installerName>-<appVersion>.msi"
// — there's no flag to omit the version. Rename in-place after each
// build so the published artifact is always BotWithUs.msi. Versioning
// remains intact internally: the MSI's ProductVersion and the embedded
// app-version are still set from appVersion, so Windows Installer still
// applies upgrade semantics correctly when the next build bumps the
// version.
val renameMsi by tasks.registering {
    val jpackageDir = layout.buildDirectory.dir("jpackage")
    inputs.dir(jpackageDir)
    outputs.file(jpackageDir.map { it.file("BotWithUs.msi") })
    doLast {
        val dir = jpackageDir.get().asFile
        val versioned = dir.listFiles { f ->
            f.isFile && f.name.startsWith("BotWithUs-") && f.name.endsWith(".msi")
        }?.singleOrNull()
        if (versioned == null) {
            throw GradleException(
                "Expected exactly one BotWithUs-<version>.msi in $dir but found " +
                        (dir.list()?.joinToString() ?: "nothing")
            )
        }
        val target = dir.resolve("BotWithUs.msi")
        if (target.exists()) target.delete()
        if (!versioned.renameTo(target)) {
            throw GradleException("Failed to rename ${versioned.name} → BotWithUs.msi")
        }
        logger.lifecycle("Renamed ${versioned.name} → ${target.name}")
    }
}

tasks.named("jpackage").configure { finalizedBy(renameMsi) }

// The imgui native ships for all three platforms inside a single artifact
// (imgui-java-app's `io/imgui/java/native-bin/`), so unlike LWJGL's per-platform
// classifier jars it cannot be reached by a dependency exclusion — dropping the
// dependency would drop imgui.app.Application, which ImGuiApp extends. It is
// stripped out of the merged module instead.
//
// The hook is `prepareModulesDir`, and getting that wrong is the whole story of
// this block. `createMergedModule` does not read the jars staged in
// `jlinkbase/nonmodjars`; it unpacks the runtime classpath itself and packs
// `jlinkbase/tmpmerged` during its own action. So neither stripping the staged
// jars nor deleting unpacked files in a `doLast` changes the linked image — both
// run, both report bytes dropped, and both leave the natives in `lib/modules`.
// Only `prepareModulesDir`'s output in `jlinkbase/jlinkjars` is late enough to
// matter and early enough for jlink to read. The check at the end of this block
// exists because that failure is silent: two earlier versions "worked" and
// shipped an unchanged image, and only `jimage list` on the built artifact
// caught it.
//
// Within the merged module the filter is by extension rather than by path, so a
// future dependency bump cannot quietly reintroduce a foreign binary — this is a
// Windows-only product, so a Linux or macOS library in its runtime image is dead
// weight whoever adds it. It is scoped to the merged module, which holds only the
// non-modular GUI dependencies, so it cannot reach sqlite-jdbc's own bundled
// natives (that module links as itself, and trimming it is a separate question).
val foreignNativeExtensions = setOf("so", "dylib")
val mergedModuleMarker = ".merged.module"
val imguiWindowsNative = "io/imgui/java/native-bin/imgui-java64.dll"

/** Rewrites [jar] without its non-Windows native entries; returns the bytes dropped. */
fun stripForeignNatives(jar: File): Long {
    val rewritten = File(jar.parentFile, "${jar.name}.stripped")
    var dropped = 0L
    ZipFile(jar).use { source ->
        ZipOutputStream(rewritten.outputStream().buffered()).use { out ->
            for (entry in source.entries()) {
                if (entry.name.substringAfterLast('.', "") in foreignNativeExtensions) {
                    dropped += entry.size
                    logger.info("merged module: dropping non-Windows native {}", entry.name)
                    continue
                }
                out.putNextEntry(ZipEntry(entry.name))
                source.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    if (dropped == 0L) {
        rewritten.delete()
    } else {
        check(jar.delete()) { "cannot replace $jar while stripping non-Windows natives" }
        check(rewritten.renameTo(jar)) { "cannot rename $rewritten to $jar" }
    }
    return dropped
}

/** Fails the build unless [jar] lost every foreign native and kept the Windows one. */
fun verifyWindowsOnlyNatives(jar: File) {
    ZipFile(jar).use { merged ->
        val names = merged.entries().toList().map { it.name }
        val foreign = names.filter { it.substringAfterLast('.', "") in foreignNativeExtensions }
        check(foreign.isEmpty()) {
            "$jar still carries non-Windows natives after stripping: $foreign"
        }
        check(names.contains(imguiWindowsNative)) {
            "$jar lost $imguiWindowsNative — the GUI cannot start without it"
        }
    }
}

tasks.named("prepareModulesDir") {
    doLast {
        val linkedJars = layout.buildDirectory.dir("jlinkbase/jlinkjars").get().asFile
        val mergedModules = (linkedJars.listFiles { file: File -> file.extension == "jar" }
            ?: emptyArray()).filter { it.name.contains(mergedModuleMarker) }
        check(mergedModules.isNotEmpty()) {
            "no merged module jar in $linkedJars — the jlink plugin's layout changed, " +
                    "and the non-Windows natives this strips would otherwise ship unnoticed"
        }
        mergedModules.forEach { merged ->
            val dropped = stripForeignNatives(merged)
            verifyWindowsOnlyNatives(merged)
            logger.lifecycle("merged module: dropped {} bytes of non-Windows natives from {}",
                dropped, merged.name)
        }
    }
}
