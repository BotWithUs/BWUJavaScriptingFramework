plugins {
    id("org.gradlex.extra-java-module-info") version "1.11"
}

dependencies {
    implementation(project(":api"))
    implementation("org.msgpack:msgpack-core:0.9.8")
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
}
