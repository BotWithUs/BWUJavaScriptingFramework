// Dummy BotScript for the SDN end-to-end test.
//
// Built as a plain (non-modular) classpath jar carrying a
// META-INF/services/com.botwithus.bot.api.BotScript entry. The heartbeat dev rig
// (LOCAL_TEST) encrypts this jar into an SDN bundle, which the custom JVM's
// jdk.internal.sdn.SdnClassLoader loads via classpath ServiceLoader. The API
// types are resolved from the parent loader at runtime, so :api is compileOnly
// and never bundled.
//
// Point the heartbeat at the built jar with
//   SDN_PLAINTEXT_BUNDLE=<...>/sdn-test-script/build/libs/sdn-test-script.jar

java {
    // Force the classpath model: no module-info, so the jar is a plain
    // service-provider artifact that the classpath ServiceLoader discovers.
    modularity.inferModulePath.set(false)
}

dependencies {
    compileOnly(project(":api"))
}
