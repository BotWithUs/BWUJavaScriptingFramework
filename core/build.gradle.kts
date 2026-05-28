plugins {
    alias(libs.plugins.gradlex.extra.java.module.info)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.msgpack.core)
    implementation(libs.gson)
    implementation(libs.logback.classic)
    // BouncyCastle: only referenced by BouncyCastlePgpVerifier (12.3).
    // Classes are loaded lazily — never touched if no repository in the
    // session has `requireSignature: true`.
    implementation(libs.bcpg.jdk18on)
    implementation(libs.bcprov.jdk18on)
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
    // BouncyCastle 1.78+ ships proper module-info entries, but the auto-
    // derived module names are stable: org.bouncycastle.pg (bcpg) and
    // org.bouncycastle.provider (bcprov). No overrides required as of 1.78.
}

// ────────────────────────────────────────────────────────────────────
// Bundle bwu.dll into the JAR at /native/bwu.dll so the framework can
// bootstrap without ever holding a bearer token: end users ship the JAR
// only, and BwuClient.resolve() extracts the bundled copy on first run.
// bwu.dll is the loader that downloads the rest of the native artifacts,
// so it cannot be delivered by that download — it travels with the JAR
// and updates whenever the application updates (it is deliberately NOT in
// data.zip).
//
// Build order: BotWithUs-Loader must be built first (Release) so
// `../BotWithUs-Loader/build/Release/bwu.dll` exists. When the file is
// missing the bundle task skips with a Gradle warning; runtime then
// falls back to BWU_DLL_PATH or a filesystem ./bwu.dll.
// Override the source path with -Pbwu.loaderDll=/abs/path/to/bwu.dll.
// ────────────────────────────────────────────────────────────────────
val loaderDllPath: String = (project.findProperty("bwu.loaderDll") as String?)
    ?: "${rootDir}/../BotWithUs-Loader/build/Release/bwu.dll"
val loaderDllSource = file(loaderDllPath)

val bundleLoaderDll by tasks.registering(Copy::class) {
    description = "Copy bwu.dll into the JAR resources at /native/bwu.dll"
    group = "build"
    from(loaderDllSource)
    into(layout.buildDirectory.dir("generated/loader-resource/native"))
    onlyIf {
        val present = loaderDllSource.isFile
        if (!present) {
            logger.warn(
                "bwu.dll not found at {} — JAR will ship without a bundled " +
                "loader; runtime must then use BWU_DLL_PATH or a ./bwu.dll " +
                "next to the executable.",
                loaderDllSource
            )
        }
        present
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/loader-resource"))

tasks.named("processResources") {
    dependsOn(bundleLoaderDll)
}

tasks.register<JavaExec>("benchmark") {
    description = "Run RPC latency benchmark against a live game server"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.rpc.RpcBenchmark"
    // Pass CLI args: ./gradlew :core:benchmark --args="-n 2000 --markdown"
}

tasks.register<JavaExec>("snapshotProbe") {
    description = "Smoke-test the shared-memory bridge against a live injected DLL"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.shm.SnapshotProbe"
    // Pass CLI args: ./gradlew :core:snapshotProbe --args="32784"
}

tasks.register<JavaExec>("eventPumpProbe") {
    description = "End-to-end check of the slice-3 bridge: pump -> bus -> subscriber"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.shm.EventPumpProbe"
}

tasks.register<JavaExec>("componentCacheProbe") {
    description = "Verify the slice-17 (iface, comp, version) component cache against a live DLL"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.impl.ComponentCacheProbe"
    // Pass CLI args: ./gradlew :core:componentCacheProbe --args="1473 0"
}

tasks.register<Test>("smokeTest") {
    description = "Maven Central / resolver smoke test (requires network)"
    group = "verification"
    useJUnitPlatform()
    systemProperty("botwithus.smoke.network", "true")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.resolver.pipeline.MavenCentralSmokeTest")
    }
}

tasks.register<Test>("liveSmokeTest") {
    description = "Live-producer smoke tests (requires NXTLibrary DLL injected into a running game)"
    group = "verification"
    useJUnitPlatform()
    systemProperty("botwithus.smoke.live", "true")
    // LiveVariableApiSmokeTest's varbit checks load NXTCache.dll via Panama, which
    // needs native access enabled; forward the cache locators from the invoking
    // command line (e.g. -Dnxtcache.dll=<...> -Dnxtcache.live=true). Harmless for
    // the other live tests, which don't touch the cache.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    listOf("nxtcache.dll", "nxtcache.path", "nxtcache.live").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.impl.snapshot.LiveLocationsSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.rpc.LiveStaleRpcSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.impl.LiveComponentApiSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.impl.LiveVariableApiSmokeTest")
    }
}
