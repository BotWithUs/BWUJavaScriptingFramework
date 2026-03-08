plugins {
    application
    id("org.beryx.jlink")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("io.github.spair:imgui-java-app:1.90.0")
}

application {
    mainClass = "com.botwithus.bot.cli.gui.ImGuiApp"
    mainModule = "com.botwithus.bot.cli"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

jlink {
    javaHome = "C:/openjdk25/jdk/build/windows-x86_64-server-release/images/jdk"
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "jbot"
    }
    forceMerge("lwjgl")
    mergedModule {
        additive = true
    }
}
