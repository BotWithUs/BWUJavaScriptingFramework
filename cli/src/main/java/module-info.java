module com.botwithus.bot.cli {
    requires com.botwithus.bot.api;
    requires com.botwithus.bot.core;
    requires com.google.gson;
    requires imgui.binding;
    requires imgui.app;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires java.desktop;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;

    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;

    opens com.botwithus.bot.cli to com.google.gson;
    opens com.botwithus.bot.cli.log to ch.qos.logback.core;

    exports com.botwithus.bot.cli;
    exports com.botwithus.bot.cli.gui;
}
