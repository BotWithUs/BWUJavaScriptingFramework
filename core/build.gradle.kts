plugins {
    id("org.gradlex.extra-java-module-info") version "1.11"
}

dependencies {
    implementation(project(":api"))
    implementation("org.msgpack:msgpack-core:0.9.8")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
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
