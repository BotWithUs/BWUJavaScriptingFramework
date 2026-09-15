// Dummy BotScript used as the payload for SDN end-to-end validation.
//
// Built as a plain (non-modular) classpath jar carrying a
// META-INF/services/com.botwithus.bot.api.BotScript entry, so the custom JVM's
// jdk.internal.sdn.SdnClassLoader can discover it via the classpath
// ServiceLoader. The API types are resolved from the parent loader at runtime,
// so :api is compileOnly and never bundled.
//
// The bundling rig that consumes this jar is internal; see the private
// SDN validation notes for how to point it here.

java {
    // Force the classpath model: no module-info, so the jar is a plain
    // service-provider artifact that the classpath ServiceLoader discovers.
    modularity.inferModulePath.set(false)
}

dependencies {
    compileOnly(project(":api"))
}
