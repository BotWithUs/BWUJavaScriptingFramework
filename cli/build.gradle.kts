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
    implementation(libs.imgui.java.app)
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
        "--enable-native-access=com.botwithus.bot.core",
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

    // Optional: LOCAL_TEST heartbeat redirect. Forwarded to the loader at
    // startup via BwuClient.setHeartbeatEndpoint (debug bwu.dll only — the
    // export is compiled out in Release). See RUN-LOCAL.md.
    project.localProperty("bwu.heartbeat.host", "BWU_HEARTBEAT_HOST")
        ?.let { jvmArgs("-Dbwu.heartbeat.host=$it") }
    project.localProperty("bwu.heartbeat.port", "BWU_HEARTBEAT_PORT")
        ?.let { jvmArgs("-Dbwu.heartbeat.port=$it") }
    project.localProperty("bwu.heartbeat.skipCertPin", "BWU_HEARTBEAT_SKIP_CERT_PIN")
        ?.let { jvmArgs("-Dbwu.heartbeat.skipCertPin=$it") }

    // Optional: agent license pubkey override. Picked up by NXTLibrary.dll
    // (debug build) via the BWU_DEV_LICENSE_PUBKEY env var. We set it on
    // *this* JVM, and bwu.dll's CreateProcessW for the game client inherits
    // the env block by default — so the agent sees it when it loads.
    val devPubKeyHex = project.localProperty("bwu.license.devPubKeyHex", "BWU_DEV_LICENSE_PUBKEY")
    val devPubKeyFile = project.localProperty("bwu.license.devPubKeyHexFile", "BWU_DEV_LICENSE_PUBKEY_FILE")
    val resolvedDevPubKey = devPubKeyFile
        ?.let { path ->
            val f = file(path)
            if (f.isFile) f.readText().trim() else null
        }
        ?: devPubKeyHex
    resolvedDevPubKey?.let { environment("BWU_DEV_LICENSE_PUBKEY", it) }
}

// Resolve the JDK that the project's Java toolchain points at. beryx-jlink
// needs an explicit JDK path for both jlink and jpackage; under Gradle 9.5
// + an auto-provisioned toolchain it cannot discover one on its own and the
// jpackageImage task NPEs in JPackageData.getDefaultJPackageHome.
val toolchainJdkPath = javaToolchains
    .launcherFor(java.toolchain)
    .map { it.metadata.installationPath.asFile }

jlink {
    val jlinkHomeOverride = project.localProperty("jlink.javaHome", "JLINK_JAVA_HOME")
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
            // bindings, Panama bridge to bwu.dll) hit the restricted Linker
            // API. Future JDKs will refuse without opt-in.
            //
            // forceMerge("lwjgl") relocates LWJGL into the synthetic
            // com.botwithus.merged.module — LWJGL's System.load() trips the
            // same restriction from there, so the merged module is on the
            // list too.
            "--enable-native-access=com.botwithus.bot.core,com.botwithus.merged.module",
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

val packageJre by tasks.registering(Zip::class) {
    dependsOn(tasks.named("jlink"))
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

// ── bundle a freshly-built release bwu.dll (optional overlay) ──────────────
// The app resolves bwu.dll at runtime via BwuClient.resolve():
//   1) BWU_DLL_PATH env var  (the dev / debug override),
//   2) ./bwu.dll next to the executable,
//   3) the bundled /native/bwu.dll resource.
// This module is the one whose /native/bwu.dll the app actually reads
// (BwuClient.resolve(getClass()) anchors on a cli class, and modular
// resource lookup is module-local).
//
// A committed baseline lives at src/main/resources/native/bwu.dll, so an
// open-source checkout always ships *some* loader and `./gradlew build`
// never fails. When a freshly-built release DLL is available — bwu.loaderDll
// in local.properties, or the default sibling-repo path — this task overlays
// it on top of that baseline (the overlay wins; see processResources below).
// When it is absent the overlay is emptied and the committed baseline ships:
// a missing DLL never breaks the build. The dev inner loop normally just sets
// BWU_DLL_PATH and skips bundling entirely.
val loaderDllPath = localProperty("bwu.loaderDll")
    ?: "${rootDir}/../BotWithUs-Loader/cmake-build-release/bwu.dll"
val loaderDllSource = file(loaderDllPath)
val loaderOverlayDir = layout.buildDirectory.dir("generated/loader-resource")

val bundleLoaderDll by tasks.registering(Sync::class) {
    description = "Overlay a freshly-built release bwu.dll onto the bundled /native/bwu.dll"
    group = "build"
    // Sync empties the destination first, so a DLL bundled by an earlier
    // build is dropped once the source path stops resolving — no stale overlay.
    into(loaderOverlayDir.map { it.dir("native") })
    if (loaderDllSource.isFile) {
        from(loaderDllSource)
    }
    doFirst {
        if (!loaderDllSource.isFile) {
            logger.lifecycle(
                "Release bwu.dll not found at {} — cli JAR ships the committed " +
                    "baseline /native/bwu.dll. Set bwu.loaderDll in local.properties " +
                    "to bundle your build, or use BWU_DLL_PATH at runtime.",
                loaderDllSource,
            )
        }
    }
}

sourceSets["main"].resources.srcDir(loaderOverlayDir)

// ── bwu.dll release-hygiene gate ───────────────────────────────────────────
// bwu.dll is the auth bootstrap shipped inside the CLI jar. Because this repo
// is open source and the DLL is a committed binary blob, this gate fails the
// build if a *debug* build, or one carrying *symbols / a PDB path*, is ever
// committed in its place:
//   - debug build  → links the debug CRT (vcruntime###d.dll / ucrtbased.dll /
//                    msvcr###d.dll …). A release links the non-debug CRT.
//   - symbols leak → a CodeView record ("RSDS") and/or an embedded *.pdb path
//                    that reveals the developer's local build directory.
// It is a pure byte scan (no PE parser, no external toolchain) so it runs on
// any OS that builds the jar. The shipped release DLL imports only
// KERNEL32.dll + VCRUNTIME140.dll and embeds no PDB path, so it passes.
val bwuDll = layout.projectDirectory.file("src/main/resources/native/bwu.dll")

val verifyBwuDll by tasks.registering {
    description = "Fails the build if the bundled bwu.dll is a debug build or carries symbols/PDB info."
    group = "verification"
    val dllFile = bwuDll.asFile
    onlyIf { dllFile.exists() }
    inputs.file(bwuDll).optional()
    val marker = layout.buildDirectory.file("bwu-dll-verified.txt")
    outputs.file(marker)
    doLast {
        val bytes = dllFile.readBytes()
        // ISO-8859-1 is a 1:1 byte→char map, so raw byte patterns survive intact.
        val text = String(bytes, Charsets.ISO_8859_1)

        // Debug CRT import names — present only in a Debug build. The trailing
        // `d` before `.dll` is what distinguishes them from the release CRT
        // (vcruntime140d.dll vs vcruntime140.dll; ucrtbased.dll vs ucrtbase.dll).
        val debugCrt = Regex(
            "((vcruntime|msvcr|msvcp|concrt|vccorlib)\\d+d|ucrtbased)\\.dll",
            RegexOption.IGNORE_CASE,
        ).findAll(text).map { it.value.lowercase() }.distinct().sorted().toList()

        // Embedded PDB path(s) — a maximal printable run ending in ".pdb".
        val pdbPaths = Regex("[ -~]+\\.pdb", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.value }.distinct().toList()
        val hasCodeView = text.contains("RSDS")

        val problems = buildList {
            if (debugCrt.isNotEmpty()) {
                add("debug CRT imports → debug build: ${debugCrt.joinToString()}")
            }
            if (pdbPaths.isNotEmpty()) {
                add("embedded PDB path → symbols leak: ${pdbPaths.joinToString()}")
            } else if (hasCodeView) {
                add("CodeView debug record (\"RSDS\") present → symbols leak")
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "bwu.dll failed the release-hygiene gate ($dllFile):\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nReplace it with a Release build linked against the non-debug CRT and " +
                    "stripped of debug info (e.g. link with /DEBUG:NONE, or strip the PDB " +
                    "reference post-link). See BotWithUs-Loader for the build."
            )
        }
        val out = marker.get().asFile
        out.parentFile.mkdirs()
        out.writeText("bwu.dll passed release-hygiene gate (${bytes.size} bytes)\n")
        logger.lifecycle(
            "bwu.dll release-hygiene gate passed: ${bytes.size} bytes, no debug CRT, no PDB path."
        )
    }
}

// Gate the DLL before it is bundled into the jar/image, and on `check` (so
// `build`, which depends on both, always runs it). processResources is the
// task that copies src/main/resources — including bwu.dll — into the output.
// The gate scans the *committed* baseline only; the optional overlay is a
// developer's local, uncommitted build.
tasks.named<Copy>("processResources").configure {
    dependsOn(verifyBwuDll, bundleLoaderDll)
    // Both the committed baseline (src/main/resources/native/bwu.dll) and the
    // overlay (generated/loader-resource, populated by bundleLoaderDll only
    // when a freshly-built release DLL is found) contribute native/bwu.dll.
    // The overlay srcDir is registered last, so INCLUDE lets the freshly-built
    // release DLL win; when the overlay is empty the committed baseline ships.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
tasks.named("check").configure { dependsOn(verifyBwuDll) }
