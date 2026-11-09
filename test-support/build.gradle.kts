// test-support module - mocks for unit-testing scripts against bot-api.
// Published beside bot-api, at the same version, by publish-api.yml.
plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    // Resolves to com.botwithus:bot-api:<this version> in the published POM,
    // because :api's only publication carries that artifactId.
    api(project(":api"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-test-support"

            pom {
                name = "BotWithUs Bot Test Support"
                description = "Mocks for unit-testing BotWithUs scripts without a game " +
                    "client: MockGameAPI, MockScriptContext, CannedSnapshot and " +
                    "InMemoryEventBus, built against bot-api of the same version."
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
            // Same static tree as :api (see api/build.gradle.kts). The publish
            // workflow passes one `-PmavenRepoDir` for both modules; left unset,
            // local builds stage into build/ for inspection.
            name = "staticTree"
            url = uri(
                providers.gradleProperty("mavenRepoDir")
                    .getOrElse(layout.buildDirectory.dir("staging-repo").get().asFile.path)
            )
        }
    }
}

tasks.javadoc {
    title = "BotWithUs Test Support $version"

    // Package mode, as in :api: with module-info excluded and module path
    // inference off, bot-api and slf4j resolve from the classpath.
    exclude("module-info.java")
    modularity.inferModulePath = false

    // Fail on javadoc errors so an empty javadoc jar cannot ship unnoticed.
    isFailOnError = true
}
