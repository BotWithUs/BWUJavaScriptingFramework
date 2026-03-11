module com.botwithus.bot.core {
    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;
    requires com.botwithus.bot.api;
    requires msgpack.core;
    requires com.google.gson;

    exports com.botwithus.bot.core;
    exports com.botwithus.bot.core.blueprint.execution;
    exports com.botwithus.bot.core.blueprint.registry;
    exports com.botwithus.bot.core.blueprint.serialization;
    exports com.botwithus.bot.core.config;
    exports com.botwithus.bot.core.crypto;
    exports com.botwithus.bot.core.impl;
    exports com.botwithus.bot.core.log;
    exports com.botwithus.bot.core.msgpack;
    exports com.botwithus.bot.core.pipe;
    exports com.botwithus.bot.core.rpc;
    exports com.botwithus.bot.core.runtime;
}
