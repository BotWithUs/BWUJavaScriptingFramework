// api module - pure Java interfaces plus SLF4J API
plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-api"

            pom {
                name = "BotWithUs Bot API"
                description = "Public scripting API for the BotWithUs Java host: " +
                    "BotScript lifecycle, the tick-scoped game snapshot, and the " +
                    "event and action surface scripts compile against."
                url = "https://github.com/BotWithUs/BWUJavaScriptingFramework"

                licenses {
                    license {
                        name = "GNU General Public License, version 3"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }

                developers {
                    developer {
                        id = "botwithus"
                        name = "BotWithUs"
                        url = "https://github.com/BotWithUs"
                    }
                }

                scm {
                    connection = "scm:git:https://github.com/BotWithUs/BWUJavaScriptingFramework.git"
                    developerConnection = "scm:git:ssh://git@github.com/BotWithUs/BWUJavaScriptingFramework.git"
                    url = "https://github.com/BotWithUs/BWUJavaScriptingFramework"
                }
            }
        }
    }

    repositories {
        maven {
            name = "staticTree"
            // A static Maven repo is just a file tree served over HTTPS. The
            // publish workflow checks out BotWithUs/maven and points
            // `-PmavenRepoDir` at that working copy, so Gradle merges
            // `maven-metadata.xml` against the versions already published
            // rather than replacing the version list with just this one.
            // Left unset, local builds stage into build/ for inspection.
            url = uri(
                providers.gradleProperty("mavenRepoDir")
                    .getOrElse(layout.buildDirectory.dir("staging-repo").get().asFile.path)
            )
        }
    }
}

dependencies {
    api(libs.slf4j.api)
}

tasks.javadoc {
    title = "BotWithUs API $version"

    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("html5", true)
        encoding = "UTF-8"
        charSet = "UTF-8"
        memberLevel = JavadocMemberLevel.PUBLIC
        tags("apiNote:a:API Note:")
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }

    // module-info is excluded so javadoc runs in package mode; with it gone the
    // module path would be empty and every `org.slf4j` import would fail to
    // resolve, so the dependencies go on the classpath instead.
    exclude("module-info.java")
    modularity.inferModulePath = false

    // Fail the build on javadoc errors. This was previously off, which let a
    // module-path misconfiguration ship an EMPTY javadoc jar and an empty Pages
    // site without anyone noticing. Warnings (missing comments) stay non-fatal.
    isFailOnError = true
}
