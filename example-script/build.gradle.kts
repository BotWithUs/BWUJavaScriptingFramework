dependencies {
    implementation(project(":api"))
    implementation(project(":quest-core"))
    compileOnly(libs.imgui.java.binding)
    testImplementation(libs.imgui.java.binding)
    testImplementation(project(":test-support"))
}

// Copy the built script JAR into the scripts/ directory for the runtime to discover
tasks.register<Copy>("installScript") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(rootProject.layout.projectDirectory.dir("scripts"))
}

tasks.named("build") {
    finalizedBy("installScript")
}
