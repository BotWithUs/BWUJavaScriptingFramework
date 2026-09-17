// quest-core — declarative quest DSL on top of the api module.
//
// The `generateQuestRegistry` task reads merged_quests.json from the
// quest.research.dir (set via local.properties or -P) and emits
// Quests.java into build/generated/. The generated source folder is
// added to the main source set so the constants are first-class.
plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
    testImplementation(project(":test-support"))
}

val generateQuestRegistry = tasks.register<QuestCodegenTask>("generateQuestRegistry") {
    project.localProperty("quest.research.dir")?.let { raw ->
        val dir = file(raw)
        if (dir.isDirectory) {
            researchDir.set(dir)
        } else {
            logger.warn(
                "quest.research.dir resolves to {} but the directory does not exist; emitting stub Quests.java.",
                dir,
            )
        }
    }
    packageName.set("com.botwithus.bot.quest")
    outputDir.set(layout.buildDirectory.dir("generated/sources/quests/java/main"))
}

sourceSets.named("main") {
    java.srcDir(generateQuestRegistry.flatMap { it.outputDir })
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-quest-core"
        }
    }
}
