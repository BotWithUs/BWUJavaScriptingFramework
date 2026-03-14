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

    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;

    opens com.botwithus.bot.cli to com.google.gson;

    exports com.botwithus.bot.cli;
    exports com.botwithus.bot.cli.gui;
}
