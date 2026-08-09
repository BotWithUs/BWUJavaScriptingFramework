plugins {
    alias(libs.plugins.gradlex.extra.java.module.info)
}

dependencies {
    implementation(project(":api"))
    // quest-core is not referenced by core's code. It is pulled onto the
    // runtime module path (and required in module-info) purely so that it
    // becomes a member of the boot ModuleLayer's configuration — the same
    // configuration LocalScriptLoader resolves each script's child layer
    // against. Quest scripts declare `requires com.botwithus.bot.quest`, so
    // without this the child-layer resolve throws FindException. This mirrors
    // how `api` reaches the boot layer for scripts to consume.
    implementation(project(":quest-core"))
    // Pulls skilling-core (+ its sqlite-jdbc/gson) onto the runtime module path
    // so it joins the boot ModuleLayer — see the matching requires in module-info.
    implementation(project(":skilling-core"))
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

tasks.register<Test>("liveDialogSelectTest") {
    description = "Live dialog-option selection by text (MUTATES the game — picks an option in the open 1188 dialog)"
    group = "verification"
    useJUnitPlatform()
    // Own opt-in, separate from liveSmokeTest: this one fires a real click.
    systemProperty("botwithus.smoke.dialog", "true")
    // Choose the option to click: -Dbotwithus.dialog.option="What's wrong?" (unset -> first option).
    System.getProperty("botwithus.dialog.option")?.let { systemProperty("botwithus.dialog.option", it) }
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.impl.LiveDialogSelectTest")
    }
}

tasks.register<Test>("liveDialogContinueTest") {
    description = "Live NPC-chat continue (MUTATES the game — advances the open 1184 'click to continue' page)"
    group = "verification"
    useJUnitPlatform()
    systemProperty("botwithus.smoke.dialog", "true")
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.impl.LiveDialogContinueTest")
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
        includeTestsMatching("com.botwithus.bot.core.impl.snapshot.LiveDynamicRegionSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.rpc.LiveStaleRpcSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.impl.LiveComponentApiSmokeTest")
        includeTestsMatching("com.botwithus.bot.core.impl.LiveVariableApiSmokeTest")
    }
}

tasks.register<Test>("sdnValidationTest") {
    description = "SDN encrypted-script validation against the custom JDK " +
            "(requires the patched jdk25u image)"
    group = "verification"
    useJUnitPlatform()

    // The root toolchain pins language version 25, and the patched jdk25u is
    // ALSO 25 — so Gradle is free to resolve some other JDK 25 and the tests
    // would skip on the assumeTrue(sdnClassLoaderPresent()) gate while looking
    // like they ran. Pin the JVM explicitly instead of trusting resolution.
    val sdnJdk = project.localProperty("sdn.jdk", "SDN_JDK")
    if (sdnJdk != null) {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        executable = "$sdnJdk/bin/java$exe"
    }

    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    doFirst {
        if (sdnJdk == null) {
            logger.warn("sdnValidationTest: no `sdn.jdk` set — falling back to the Gradle " +
                    "toolchain JVM, which is probably NOT the patched JDK. Every test will " +
                    "skip. Set -Psdn.jdk=<path to build/*/images/jdk> or add sdn.jdk to " +
                    "local.properties.")
        } else {
            logger.lifecycle("sdnValidationTest: using JVM at $sdnJdk")
        }
    }

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("com.botwithus.bot.core.crypto.SdnForgedEnvelopeTest")
    }
}
