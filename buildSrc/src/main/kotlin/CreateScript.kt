import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Gradle task that scaffolds a new BotScript module under the project root.
 *
 * Invocation: `./gradlew createScript --scriptName=foo [--author="Jane"]
 *                                     [--scriptDescription="..."]`
 *
 * The task generates a child module containing:
 *  - `build.gradle.kts` with `implementation(project(":api"))`, an
 *    `installScript` `Copy` task, and a `maven-publish` block so the
 *    script can be published to Nexus (item 12's consumer side reads
 *    the same shape).
 *  - `module-info.java` declaring `provides com.botwithus.bot.api.BotScript
 *    with <Name>Script;`.
 *  - `<Name>Script.java` — a stub `BotScript` implementation annotated
 *    with `@ScriptManifest` whose `onStart` / `onLoop` / `onStop` bodies
 *    log a single line each.
 *
 * The module entry (`:<dir-name>`) is appended to `additionalScripts.txt`
 * at the project root; `settings.gradle.kts` reads that file and `include`s
 * each entry, which keeps `settings.gradle.kts` itself stable.
 *
 * Inputs are `Property<String>` so Gradle can serialize them for
 * configuration-cache compatibility.
 */
abstract class CreateScript : DefaultTask() {

    @get:Input
    @get:Option(option = "scriptName", description = "Name of the new script (kebab- or PascalCase).")
    abstract val scriptName: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "author", description = "Author shown in @ScriptManifest.")
    abstract val author: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "scriptDescription", description = "Description shown in @ScriptManifest.")
    abstract val scriptDescription: Property<String>

    init {
        group = "scaffolding"
        description = "Scaffold a new BotScript module under the project root."
    }

    @TaskAction
    fun create() {
        val rawName = scriptName.orNull
                ?: throw GradleException("--scriptName is required (e.g. --scriptName=woodcutter)")
        val names = ScriptNames.of(rawName)
        val projectRoot = project.rootDir.toPath()
        val moduleDir = projectRoot.resolve(names.moduleDirName)
        if (Files.exists(moduleDir)) {
            throw GradleException("Module directory already exists: $moduleDir")
        }

        val packageDir = moduleDir.resolve("src/main/java/${names.packageName.replace('.', '/')}")
        Files.createDirectories(packageDir)
        val authorValue = author.getOrElse("")
        val descriptionValue = scriptDescription.getOrElse("")

        Files.writeString(moduleDir.resolve("build.gradle.kts"), buildScript(names))
        Files.writeString(moduleDir.resolve("src/main/java/module-info.java"), moduleInfo(names))
        Files.writeString(packageDir.resolve("${names.className}.java"),
                scriptSource(names, authorValue, descriptionValue))

        appendToAdditionalScripts(projectRoot.resolve(ADDITIONAL_SCRIPTS_FILE), names.moduleDirName)

        logger.lifecycle("Created script module: {}", names.moduleDirName)
        logger.lifecycle("  package: {}", names.packageName)
        logger.lifecycle("  class:   {}.{}", names.packageName, names.className)
        logger.lifecycle("Re-run Gradle so settings.gradle.kts picks up the new module.")
    }

    private fun buildScript(names: ScriptNames): String =
            """
            |plugins {
            |    `java-library`
            |    `maven-publish`
            |}
            |
            |dependencies {
            |    implementation(project(":api"))
            |}
            |
            |tasks.register<Copy>("installScript") {
            |    dependsOn(tasks.jar)
            |    from(tasks.jar.get().archiveFile)
            |    into(rootProject.layout.projectDirectory.dir("scripts"))
            |}
            |
            |tasks.named("build") {
            |    finalizedBy("installScript")
            |}
            |
            |publishing {
            |    publications {
            |        create<MavenPublication>("mavenJava") {
            |            from(components["java"])
            |            groupId = "com.botwithus.scripts"
            |            artifactId = "${names.moduleDirName}"
            |        }
            |    }
            |}
            |""".trimMargin()

    private fun moduleInfo(names: ScriptNames): String =
            """
            |module ${names.moduleName} {
            |    requires $API_MODULE_NAME;
            |
            |    provides $BOTSCRIPT_FQN
            |        with ${names.packageName}.${names.className};
            |}
            |""".trimMargin()

    private fun scriptSource(names: ScriptNames, authorValue: String, descriptionValue: String): String {
        val authorAttr = if (authorValue.isEmpty()) "" else """, author = "$authorValue""""
        val descriptionAttr = if (descriptionValue.isEmpty()) "" else """, description = "$descriptionValue""""
        return """
        |package ${names.packageName};
        |
        |import $BOTSCRIPT_FQN;
        |import $CONTEXT_FQN;
        |import $MANIFEST_FQN;
        |
        |import org.slf4j.Logger;
        |import org.slf4j.LoggerFactory;
        |
        |@ScriptManifest(name = "${names.manifestName}", version = "1.0"$authorAttr$descriptionAttr)
        |public class ${names.className} implements BotScript {
        |
        |    private static final Logger log = LoggerFactory.getLogger(${names.className}.class);
        |    private static final int LOOP_DELAY_MS = $LOOP_DELAY_DEFAULT_MS;
        |
        |    @Override
        |    public void onStart(ScriptContext ctx) {
        |        log.info("Started.");
        |    }
        |
        |    @Override
        |    public int onLoop() {
        |        return LOOP_DELAY_MS;
        |    }
        |
        |    @Override
        |    public void onStop() {
        |        log.info("Stopped.");
        |    }
        |}
        |""".trimMargin()
    }

    private fun appendToAdditionalScripts(file: Path, moduleDirName: String) {
        val entry = ":$moduleDirName"
        val existing = if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        if (existing.any { it.trim() == entry }) {
            return
        }
        val openOptions = if (Files.exists(file)) {
            arrayOf(StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
        Files.writeString(file, "$entry\n", *openOptions)
    }

    private companion object {
        const val ADDITIONAL_SCRIPTS_FILE = "additionalScripts.txt"
        const val API_MODULE_NAME = "com.botwithus.bot.api"
        const val BOTSCRIPT_FQN = "com.botwithus.bot.api.BotScript"
        const val MANIFEST_FQN = "com.botwithus.bot.api.ScriptManifest"
        const val CONTEXT_FQN = "com.botwithus.bot.api.ScriptContext"
        const val LOOP_DELAY_DEFAULT_MS = 1000
    }
}
