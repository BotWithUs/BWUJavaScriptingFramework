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
        // -Werror is on. The three suppressed lints below are deliberate:
        //   -restricted        — Panama (java.lang.foreign) is the SUPPORTED
        //                        alternative to JNI per the project's rules;
        //                        every native call to bwu / NXTCache / worldwalker
        //                        is intentionally a restricted method.
        //   -this-escape       — flagged on UI / native bridge constructors that
        //                        publish `this` for callbacks (ImGui Application
        //                        subclasses, downcall handle binding). Fixing is
        //                        a constructor redesign, not a lint sweep.
        //   -requires-automatic — module-info entries for msgpack-core and other
        //                        automatic-module dependencies; addressed by
        //                        extra-java-module-info shims where available.
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all,-restricted,-this-escape,-requires-automatic,-requires-transitive-automatic,-text-blocks",
                "-Werror",
                "-parameters",
            )
        )
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }
}

tasks.register<CreateScript>("createScript")
