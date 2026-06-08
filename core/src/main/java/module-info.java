module com.botwithus.bot.core {
    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;
    uses com.botwithus.bot.core.resolver.driver.RepositoryDriver;
    // transitive: core's exported types (e.g. ManagementContextImpl, GameAPIImpl)
    // reference api types in their public signatures. Re-exporting api avoids
    // 160+ -Xlint [exports] warnings about consumers needing both modules.
    requires transitive com.botwithus.bot.api;
    // transitive: SearchService and HttpTransport's public methods accept
    // HttpClient — re-export so callers don't need to require java.net.http.
    requires transitive java.net.http;
    requires msgpack.core;
    requires com.google.gson;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires java.xml;
    requires jdk.httpserver;
    // BouncyCastle (PGP). Required at module-resolution time so the
    // resolver's BouncyCastlePgpVerifier can call into it, but the
    // verifier itself only loads BC classes on the first verify() call,
    // so sessions with no signed repos pay no runtime cost.
    requires org.bouncycastle.pg;
    requires org.bouncycastle.provider;

    provides com.botwithus.bot.core.resolver.driver.RepositoryDriver
            with com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;

    exports com.botwithus.bot.core;
    exports com.botwithus.bot.core.cache;
    exports com.botwithus.bot.core.config;
    exports com.botwithus.bot.core.crypto;
    exports com.botwithus.bot.core.impl;
    exports com.botwithus.bot.core.impl.snapshot;
    exports com.botwithus.bot.core.msgpack;
    exports com.botwithus.bot.core.pipe;
    exports com.botwithus.bot.core.rpc;
    exports com.botwithus.bot.core.runtime;
    exports com.botwithus.bot.core.shm;
    exports com.botwithus.bot.core.worldwalker;
    exports com.botwithus.bot.core.resolver;
    exports com.botwithus.bot.core.resolver.config;
    exports com.botwithus.bot.core.resolver.driver;
    exports com.botwithus.bot.core.resolver.install;
    exports com.botwithus.bot.core.resolver.metadata;
    exports com.botwithus.bot.core.resolver.pgp;
    exports com.botwithus.bot.core.resolver.pipeline;
    exports com.botwithus.bot.core.resolver.search;
    exports com.botwithus.bot.core.resolver.transport;
}
