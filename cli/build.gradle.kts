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

jlink {
    val jlinkHome = providers.gradleProperty("jlink.javaHome")
        .orElse(providers.environmentVariable("JLINK_JAVA_HOME"))
    if (jlinkHome.isPresent) {
        javaHome.set(file(jlinkHome.get()))
    }
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "jbot"
    }
    forceMerge("lwjgl")
    mergedModule {
        additive = true
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
