module com.botwithus.bot.core {
    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;
    uses com.botwithus.bot.core.resolver.driver.RepositoryDriver;
    requires com.botwithus.bot.api;
    requires msgpack.core;
    requires com.google.gson;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires java.xml;
    requires java.net.http;
    requires jdk.httpserver;

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
    exports com.botwithus.bot.core.loader;
    exports com.botwithus.bot.core.shm;
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
