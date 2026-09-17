// skilling-core — the shared "skilling SDK" + Atlas data layer on top of the
// api module. Separate per-skill script projects (woodcutting-script, future
// mining/smithing) `requires com.botwithus.bot.skilling` and compile against
// the SkillScript/GatherScript bases, the Atlas reader, and the banking helper.
//
// Published as `bot-skilling` (like quest-core's `bot-quest-core`) so an
// external script repo can consume it. core pulls this onto the runtime module
// path (see core/build.gradle.kts + core/module-info.java) so each script's
// child ModuleLayer can resolve `requires com.botwithus.bot.skilling`.
plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
    // gson (tree API only — no reflective binding) parses the recipe.json blob
    // baked into the Atlas; sqlite-jdbc reads resolved.sqlite. Both ship an
    // Automatic-Module-Name (com.google.gson / org.xerial.sqlitejdbc), so no
    // gradlex module-info transform is needed.
    implementation(libs.gson)
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":test-support"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // AtlasClosureTest skips cleanly (JUnit Assumptions) when no resolved.sqlite
    // is present. Forward the dev override so it can run against a local Atlas:
    //   ./gradlew :skilling-core:test -Dbotwithus.atlas=<path>\resolved.sqlite
    System.getProperty("botwithus.atlas")?.let { systemProperty("botwithus.atlas", it) }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-skilling"
        }
    }
}
