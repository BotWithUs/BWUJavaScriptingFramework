plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-test-support"
        }
    }
}
