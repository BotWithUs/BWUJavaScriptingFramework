import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CreateScriptTaskTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setup() {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
            rootProject.name = "create-script-test"
        """.trimIndent())

        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            tasks.register<CreateScript>("createScript")
        """.trimIndent())

        val buildSrcMainKotlin = projectDir.resolve("buildSrc/src/main/kotlin")
        Files.createDirectories(buildSrcMainKotlin)
        Files.writeString(projectDir.resolve("buildSrc/build.gradle.kts"), """
            plugins {
                `kotlin-dsl`
            }
            repositories {
                gradlePluginPortal()
                mavenCentral()
            }
        """.trimIndent())
        copySource(buildSrcMainKotlin.resolve("CreateScript.kt"), CREATE_SCRIPT_SOURCE)
        copySource(buildSrcMainKotlin.resolve("ScriptNames.kt"), SCRIPT_NAMES_SOURCE)
    }

    @Test
    fun createScript_generatesModuleFiles() {
        val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("createScript", "--scriptName=foo-bar", "--author=Jane",
                        "--scriptDescription=Demo script")
                .forwardOutput()
                .build()

        assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS,
                result.task(":createScript")?.outcome)

        val moduleDir = projectDir.resolve("foo-bar")
        val classFile = moduleDir.resolve("src/main/java/com/botwithus/bot/scripts/foobar/FooBarScript.java")
        val moduleInfo = moduleDir.resolve("src/main/java/module-info.java")
        val buildFile = moduleDir.resolve("build.gradle.kts")
        val additionalScripts = projectDir.resolve("additionalScripts.txt")

        assertTrue(Files.exists(classFile), "class file should exist: $classFile")
        assertTrue(Files.exists(moduleInfo), "module-info should exist")
        assertTrue(Files.exists(buildFile), "build script should exist")
        assertTrue(Files.exists(additionalScripts), "additionalScripts.txt should exist")

        val moduleInfoText = Files.readString(moduleInfo)
        assertTrue(moduleInfoText.contains("module com.botwithus.bot.scripts.foobar"),
                "module declaration: $moduleInfoText")
        assertTrue(moduleInfoText.contains(
                "provides com.botwithus.bot.api.BotScript"),
                "provides line: $moduleInfoText")
        assertTrue(moduleInfoText.contains(
                "with com.botwithus.bot.scripts.foobar.FooBarScript"),
                "with line: $moduleInfoText")

        val classText = Files.readString(classFile)
        assertTrue(classText.contains("@ScriptManifest"), "manifest annotation present")
        assertTrue(classText.contains("public class FooBarScript implements BotScript"),
                "class declaration: $classText")
        assertTrue(classText.contains("""author = "Jane""""), "author propagated")
        assertTrue(classText.contains("""description = "Demo script""""), "description propagated")

        val buildText = Files.readString(buildFile)
        assertTrue(buildText.contains("""implementation(project(":api"))"""),
                "api dependency declared")
        assertTrue(buildText.contains("installScript"), "installScript task declared")
        assertTrue(buildText.contains("maven-publish"), "maven-publish plugin declared")
        assertTrue(buildText.contains("MavenPublication"), "MavenPublication block declared")

        assertEquals(":foo-bar", Files.readAllLines(additionalScripts).first().trim())
    }

    @Test
    fun createScript_appendsToExistingAdditionalScripts() {
        val additionalScripts = projectDir.resolve("additionalScripts.txt")
        Files.writeString(additionalScripts, ":already-here\n")

        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("createScript", "--scriptName=second")
                .forwardOutput()
                .build()

        val lines = Files.readAllLines(additionalScripts)
        assertEquals(listOf(":already-here", ":second"), lines)
    }

    @Test
    fun createScript_failsWhenScriptNameMissing() {
        val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("createScript")
                .forwardOutput()
                .buildAndFail()

        assertTrue(result.output.contains("scriptName"),
                "expected error mentioning scriptName, got: ${result.output}")
    }

    @Test
    fun createScript_failsWhenModuleDirectoryAlreadyExists() {
        Files.createDirectories(projectDir.resolve("existing"))

        val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("createScript", "--scriptName=existing")
                .forwardOutput()
                .buildAndFail()

        assertTrue(result.output.contains("Module directory already exists"),
                "expected pre-existing-dir error, got: ${result.output}")
    }

    private fun copySource(destination: Path, source: String) {
        Files.createDirectories(destination.parent)
        Files.writeString(destination, source)
    }

    companion object {
        private val CREATE_SCRIPT_SOURCE: String by lazy {
            readResourceClassFromSource("CreateScript.kt")
        }
        private val SCRIPT_NAMES_SOURCE: String by lazy {
            readResourceClassFromSource("ScriptNames.kt")
        }

        private fun readResourceClassFromSource(fileName: String): String {
            val projectRoot = locateBuildSrcRoot()
            val path = projectRoot.resolve("src/main/kotlin/$fileName")
            return Files.readString(path)
        }

        private fun locateBuildSrcRoot(): Path {
            val cwd = Path.of("").toAbsolutePath()
            var candidate: Path? = cwd
            while (candidate != null) {
                val maybe = candidate.resolve("buildSrc")
                if (Files.isDirectory(maybe.resolve("src/main/kotlin"))) {
                    return maybe
                }
                candidate = candidate.parent
            }
            return cwd
        }
    }
}
