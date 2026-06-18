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

tasks.register<JavaExec>("sceneObjectsProbe") {
    description = "Exercise api.objects().query().namedExact(...).withinDistance(...).nearest() against the live SHM"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.shm.SceneObjectsLiveProbe"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Forward NXTCache locators set on the gradle command line so getLocationType resolves.
    listOf("nxtcache.dll", "nxtcache.path", "nxtcache.live").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // Pass CLI args: ./gradlew :core:sceneObjectsProbe --args="32996 8 Tree"
}

tasks.register<JavaExec>("eventPumpProbe") {
    description = "End-to-end check of the slice-3 bridge: pump -> bus -> subscriber"
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.botwithus.bot.core.shm.EventPumpProbe"
}

tasks.register<Test>("worldwalkerE2ETest") {
    description = "End-to-end Panama upcall test against a real worldwalker.dll + artifact"
    group = "verification"
    useJUnitPlatform()
    // Forward the WW locators to the test JVM. The two @EnabledIfSystemProperty
    // gates on WorldWalkerExecutorE2ETest cause the case to skip cleanly when
    // either prop is missing, so this task is safe to run unconditionally.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    listOf("worldwalker.dll", "worldwalker.testArtifact").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.worldwalker.WorldWalkerExecutorE2ETest")
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
