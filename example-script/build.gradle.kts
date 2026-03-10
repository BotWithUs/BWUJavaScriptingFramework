val imguiVersion = "1.90.0"

dependencies {
    implementation(project(":api"))
    compileOnly("io.github.spair:imgui-java-binding:$imguiVersion")
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
