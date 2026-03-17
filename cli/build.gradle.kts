plugins {
    application
    id("org.beryx.jlink")
    id("org.gradlex.extra-java-module-info") version "1.11"
}

val lwjglVersion = "3.3.6"
val imguiVersion = "1.90.0"
val lwjglNatives = "natives-windows"
val javafxVersion = "21.0.5"
val nativeAccessModules = listOf(
    "org.lwjgl",
    "org.lwjgl.glfw",
    "org.lwjgl.opengl",
    "imgui.binding",
    "javafx.graphics",
)
val nativeAccessArg = "--enable-native-access=${nativeAccessModules.joinToString(",")}"
val javafxModulesArg = "--add-modules=javafx.base,javafx.graphics,javafx.controls"

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.github.spair:imgui-java-app:$imguiVersion")
    runtimeOnly("org.openjfx:javafx-base:$javafxVersion:win")
    runtimeOnly("org.openjfx:javafx-graphics:$javafxVersion:win")
    runtimeOnly("org.openjfx:javafx-controls:$javafxVersion:win")
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
    applicationDefaultJvmArgs = listOf(nativeAccessArg, javafxModulesArg)
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
    jvmArgs(
        nativeAccessArg,
        javafxModulesArg,
        "-Dorg.lwjgl.librarypath=${layout.buildDirectory.dir("natives").get().asFile.absolutePath}",
    )
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
        jvmArgs.add(nativeAccessArg)
        jvmArgs.add(javafxModulesArg)
    }
    forceMerge("lwjgl")
    mergedModule {
        additive = true
    }
}
