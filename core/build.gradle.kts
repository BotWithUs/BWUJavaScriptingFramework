plugins {
    alias(libs.plugins.gradlex.extra.java.module.info)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.msgpack.core)
    implementation(libs.gson)
    implementation(libs.logback.classic)
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
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
