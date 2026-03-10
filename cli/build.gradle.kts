plugins {
    application
    id("org.beryx.jlink")
    id("org.gradlex.extra-java-module-info") version "1.11"
}

val lwjglVersion = "3.3.6"
val imguiVersion = "1.90.0"
val lwjglNatives = "natives-windows"

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("io.github.spair:imgui-java-app:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
}

application {
    mainClass = "com.botwithus.bot.cli.gui.ImGuiApp"
    mainModule = "com.botwithus.bot.cli"
}

val extractNatives by tasks.registering(Copy::class) {
    val nativeJars = configurations.runtimeClasspath.get().filter { it.name.contains("natives") }
    nativeJars.forEach { from(zipTree(it)) }
    into(layout.buildDirectory.dir("natives"))
    include("**/*.dll", "**/*.so", "**/*.dylib")
}

tasks.named<JavaExec>("run") {
    dependsOn(extractNatives)
    workingDir = rootProject.projectDir
    jvmArgs("-Dorg.lwjgl.librarypath=${layout.buildDirectory.dir("natives").get().asFile.absolutePath}")
}

jlink {
    val jlinkHome = providers.gradleProperty("jlink.javaHome")
        .orElse(providers.environmentVariable("JLINK_JAVA_HOME"))
    if (jlinkHome.isPresent) {
        javaHome.set(file(jlinkHome.get()))
    }
    options.set(listOf("--strip-debug", "--compress", "zip-6", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "jbot"
    }
    forceMerge("lwjgl")
    mergedModule {
        additive = true
    }
}
