module com.botwithus.bot.cli {
    // transitive: cli's exported gui classes (UserModeRenderer, ManagementScriptsPanel,
    // ImGuiApp, etc.) expose api / core / desktop / imgui-app types in their public
    // signatures. Re-exporting avoids -Xlint [exports] warnings on every panel ctor.
    requires transitive com.botwithus.bot.api;
    requires transitive com.botwithus.bot.core;
    requires transitive java.desktop;
    requires transitive imgui.app;
    // transitive: LogBufferAppender extends AppenderBase<ILoggingEvent> in its public
    // signature; required transitively so consumers of cli.log can subclass / wire it.
    requires transitive ch.qos.logback.core;
    requires transitive ch.qos.logback.classic;
    requires com.google.gson;
    requires imgui.binding;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires java.net.http;

    uses com.botwithus.bot.api.BotScript;
    uses com.botwithus.bot.api.script.ManagementScript;

    opens com.botwithus.bot.cli to com.google.gson;
    opens com.botwithus.bot.cli.log to ch.qos.logback.core;

    exports com.botwithus.bot.cli;
    // Exported because CliContext leaks LogBuffer / LogCapture / StreamManager /
    // CommandRegistry through its public methods. Consumers (panels, commands)
    // legitimately depend on these types so they must be addressable.
    exports com.botwithus.bot.cli.command;
    exports com.botwithus.bot.cli.log;
    exports com.botwithus.bot.cli.stream;
    exports com.botwithus.bot.cli.gui;
    exports com.botwithus.bot.cli.gui.usermode;
}
