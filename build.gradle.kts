plugins {
    id("java")
    id("org.beryx.jlink") version "3.1.1" apply false
}

group = "com.botwithus"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "java")

    group = "com.botwithus"
    version = "1.0-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        modularity.inferModulePath = true
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.mockito:mockito-core:5.14.2")
        testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
