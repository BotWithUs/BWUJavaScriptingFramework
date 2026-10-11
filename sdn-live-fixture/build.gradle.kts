// Payload jars for cli's sdnLiveCheck, the live check of the SDN install screens.
//
// One jar per case, each its own NAMED module. Separate modules, not one jar with
// four providers: the host pairs a delivered script with its catalogue entry by
// the jar it came in, and loads each jar into a module layer of its own, so a
// case needs a jar to itself. The four are expanded from one template so they
// cannot drift apart in anything but their names.
//
// None of these reach a user: nothing installs them, and the jlink image and the
// installer never read this project.

val sdnLiveVariants = listOf("craftstore", "blankstore", "craftpicker", "blankpicker")

val sdnLiveFixtures by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

sdnLiveVariants.forEach { variant ->
    val generate = tasks.register<Copy>("generate${variant.replaceFirstChar(Char::uppercase)}Sources") {
        from("src/template")
        into(layout.buildDirectory.dir("generated/sdn-live/$variant"))
        // module-info stays at the root; the class goes under its package path.
        eachFile {
            if (name != "module-info.java") {
                path = "com/botwithus/sdnlive/$variant/$name"
            }
        }
        includeEmptyDirs = false
        expand("variant" to variant)
    }
    val sourceSet = sourceSets.create(variant) {
        java.srcDir(generate)
    }
    dependencies.add(sourceSet.implementationConfigurationName, project(":api"))
    val jar = tasks.register<Jar>(sourceSet.jarTaskName) {
        archiveFileName = "sdn-live-$variant.jar"
        from(sourceSet.output)
    }
    artifacts.add(sdnLiveFixtures.name, jar)
    tasks.named("assemble") { dependsOn(jar) }
}

// The project's own source set is empty; an empty jar beside the four is only noise.
tasks.named<Jar>("jar") { enabled = false }
