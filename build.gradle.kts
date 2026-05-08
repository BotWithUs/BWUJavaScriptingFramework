import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.beryx.jlink) apply false
}

group = "com.botwithus"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java")

    group = "com.botwithus"
    version = "1.0-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        modularity.inferModulePath = true
    }

    repositories {
        mavenCentral()
    }

    // Type-safe `libs.*` accessors are not generated inside the root
    // `subprojects { ... }` block, so look the catalog up by name and
    // resolve aliases through `findLibrary` / `findBundle`.
    val libs = rootProject.extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")

    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findBundle("mockito").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -Werror intentionally omitted: an initial enable produced compile
        // failures across the modules (predominantly unchecked, deprecation,
        // and module-path warnings from extra-java-module-info shims and the
        // existing API surface). Cleaning those up requires touching .java
        // sources, which is out of scope for this build-infrastructure pass.
        // TODO: re-enable -Werror once the warning backlog is cleared. A
        // focused sweep with `-Xlint:all -Werror` will surface the full
        // count; do that pass before flipping this back on.
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                "-parameters",
            )
        )
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }
}
