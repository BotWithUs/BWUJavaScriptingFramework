module com.botwithus.bot.core {
    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;
    // transitive: core's exported types (e.g. ManagementContextImpl, GameAPIImpl)
    // reference api types in their public signatures. Re-exporting api avoids
    // 160+ -Xlint [exports] warnings about consumers needing both modules.
    requires transitive com.botwithus.bot.api;
    requires msgpack.core;
    requires com.google.gson;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires java.xml;

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
}
