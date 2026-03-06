plugins {
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("io.github.spair:imgui-java-app:1.90.0")
}

application {
    mainClass = "com.botwithus.bot.cli.gui.ImGuiApp"
}

// Put the API JAR on the module path so the boot layer has com.botwithus.bot.api
// as a named module. ScriptLoader needs this to resolve script modules.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    doFirst {
        val apiJar = classpath.files.first { it.name.startsWith("api") && it.name.endsWith(".jar") }
        classpath = files(classpath.files - apiJar)
        jvmArgs(
            "--module-path", apiJar.absolutePath,
            "--add-modules", "com.botwithus.bot.api"
        )
    }
}
