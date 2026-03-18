module com.botwithus.bot.api {
    requires transitive org.slf4j;

    exports com.botwithus.bot.api;
    exports com.botwithus.bot.api.event;
    exports com.botwithus.bot.api.model;
    exports com.botwithus.bot.api.query;
    exports com.botwithus.bot.api.inventory;
    exports com.botwithus.bot.api.entities;
    exports com.botwithus.bot.api.isc;
    exports com.botwithus.bot.api.blueprint;
    exports com.botwithus.bot.api.util;
    exports com.botwithus.bot.api.config;
    exports com.botwithus.bot.api.constants;
    exports com.botwithus.bot.api.log;
    exports com.botwithus.bot.api.script;
    exports com.botwithus.bot.api.ui;
}
