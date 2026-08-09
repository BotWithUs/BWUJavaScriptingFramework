module com.botwithus.bot.core {
    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;
    // transitive: core's exported types (e.g. ManagementContextImpl, GameAPIImpl)
    // reference api types in their public signatures. Re-exporting api avoids
    // 160+ -Xlint [exports] warnings about consumers needing both modules.
    requires transitive com.botwithus.bot.api;
    // Not used by core's code. Required so quest-core joins the boot
    // ModuleLayer's configuration, letting LocalScriptLoader resolve quest
    // scripts' `requires com.botwithus.bot.quest` against the parent layer
    // (same mechanism by which scripts see api). Non-transitive: core's own
    // exported API never references quest types, so it must not leak to cli.
    requires com.botwithus.bot.quest;
    // Same boot-layer trick as quest above: skilling scripts declare
    // `requires com.botwithus.bot.skilling`, so skilling-core must join the boot
    // ModuleLayer's configuration (and pull sqlite-jdbc + gson with it) for
    // LocalScriptLoader to resolve those child layers. Non-transitive: core's
    // own exported API never references skilling types.
    requires com.botwithus.bot.skilling;
    requires msgpack.core;
    requires com.google.gson;
    // Reader for the baked gameval name index (core.gameval.SqliteGamevalIndex).
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires java.xml;

    exports com.botwithus.bot.core;
    exports com.botwithus.bot.core.cache;
    exports com.botwithus.bot.core.config;
    exports com.botwithus.bot.core.crypto;
    exports com.botwithus.bot.core.gameval;
    exports com.botwithus.bot.core.impl;
    exports com.botwithus.bot.core.impl.snapshot;
    exports com.botwithus.bot.core.msgpack;
    exports com.botwithus.bot.core.pipe;
    exports com.botwithus.bot.core.rpc;
    exports com.botwithus.bot.core.runtime;
    exports com.botwithus.bot.core.shm;
    exports com.botwithus.bot.core.worldwalker;
}
