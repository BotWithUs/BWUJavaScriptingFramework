// The host front-end: the top of the module graph. Nothing requires it, so it
// exports nothing. Scripts load in child layers resolved against the boot layer
// this module lives in, and an exported package here would be script API by
// accident, just as one in com.botwithus.bot.core would. The main class needs no
// export to be launched; the logging appender is reached through the `opens` below.
//
// Its requires are plain rather than transitive for the same reason: with no
// exports there is no signature for a reader to need them for, and a transitive
// require would only pass these modules on to anything that requires this one.
module com.botwithus.bot.cli {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires java.desktop;
    requires imgui.app;
    requires imgui.binding;
    requires ch.qos.logback.core;
    requires ch.qos.logback.classic;
    requires com.google.gson;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;

    // For user scripts only. Host code must not use this module: the host makes
    // no HTTP requests, and the launcher sends all of its traffic. A script layer
    // resolves against the boot layer alone, and the shipped launcher starts this
    // module with a fixed argument line, so this require is what puts the JDK
    // HTTP client module in the boot layer (and in the linked image) for a script
    // that requires it. HostMakesNoHttpRequestsTest allows exactly this line.
    requires java.net.http;

    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;

    // logback instantiates LogBufferAppender from logback.xml.
    opens com.botwithus.bot.cli.log to ch.qos.logback.core;
}
