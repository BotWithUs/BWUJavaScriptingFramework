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
    // cache-backed config-type lookups (item/npc/loc/quest/etc.). Either
    // export these as environment variables or pass them on the command
    // line: ./gradlew :cli:run -Pnxtcache.dll=... -Pnxtcache.path=...
    val nxtcacheDll = providers.gradleProperty("nxtcache.dll")
        .orElse(providers.environmentVariable("NXTCACHE_DLL"))
    val nxtcachePath = providers.gradleProperty("nxtcache.path")
        .orElse(providers.environmentVariable("NXTCACHE_PATH"))
    if (nxtcacheDll.isPresent) {
        jvmArgs("-Dnxtcache.dll=${nxtcacheDll.get()}")
    }
    if (nxtcachePath.isPresent) {
        jvmArgs("-Dnxtcache.path=${nxtcachePath.get()}")
    }
}

// Resolve the JDK that the project's Java toolchain points at. beryx-jlink
// needs an explicit JDK path for both jlink and jpackage; under Gradle 9.5
// + an auto-provisioned toolchain it cannot discover one on its own and the
// jpackageImage task NPEs in JPackageData.getDefaultJPackageHome.
val toolchainJdkPath = javaToolchains
    .launcherFor(java.toolchain)
    .map { it.metadata.installationPath.asFile }

jlink {
    val jlinkHomeOverride = providers.gradleProperty("jlink.javaHome")
        .orElse(providers.environmentVariable("JLINK_JAVA_HOME"))
    if (jlinkHomeOverride.isPresent) {
        javaHome.set(file(jlinkHomeOverride.get()))
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
    val navDataDir = providers.gradleProperty("navDataDir")
        .orElse(providers.environmentVariable("NAV_DATA_DIR"))
    if (navDataDir.isPresent) {
        from(navDataDir.get()) {
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
tasks.named("processResources").configure { dependsOn(verifyBwuDll) }
tasks.named("check").configure { dependsOn(verifyBwuDll) }
