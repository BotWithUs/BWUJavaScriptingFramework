// "module": every export is qualified to com.botwithus.bot.cli, which depends on core and
// so is never observable while core compiles. javac warns "module not found" for
// that, and -Werror makes it fatal. The suppression covers only this lint category, whose
// other job is the same unknown-module check on `exports ... to` / `opens ... to`.
@SuppressWarnings("module")
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
    requires com.botwithus.msgpack;
    requires com.google.gson;
    // Reader for the baked gameval name index (core.gameval.SqliteGamevalIndex).
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;
    requires java.xml;
    // Alert notifiers (core.alerts) post to ntfy, Slack and Discord with the JDK
    // HTTP client. Kept in core rather than cli so the notifiers are built and
    // tested in CI, and sit beside the credential store holding their secrets.
    requires java.net.http;

    // Every export is qualified to the host front-end. Scripts load in child layers
    // resolved against the boot layer this module lives in, so an unqualified export
    // would make that package part of the script API. It is not: scripts are given
    // com.botwithus.bot.api, quest and skilling, and nothing of this module.
    // ModuleExportsTest pins this; a new export names its target here too.
    //
    // Not exported at all, because nothing outside this module uses them: the root
    // package (JBotApplication, a main class), msgpack and worldwalker.
    exports com.botwithus.bot.core.alerts to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.cache to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.config to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.crypto to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.gameval to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.impl to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.impl.snapshot to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.launcher to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.pipe to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.report to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.rpc to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.runlog to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.runtime to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.sdn to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.secrets to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.shm to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.voice to com.botwithus.bot.cli;
    exports com.botwithus.bot.core.window to com.botwithus.bot.cli;
}
