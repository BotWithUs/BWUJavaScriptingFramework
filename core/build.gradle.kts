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
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.impl.snapshot.LiveLocationsSmokeTest")
    }
}
