package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ui.ExternalWindowScriptUI;
import com.botwithus.bot.api.ui.ScriptUI;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ScriptUIHostSupport {

    private static final ExecutorService EXTERNAL_UI_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "external-script-ui");
        thread.setDaemon(true);
        return thread;
    });

    private ScriptUIHostSupport() {
    }

    static boolean isExternalWindowScriptUI(ScriptUI ui) {
        return ui instanceof ExternalWindowScriptUI;
    }

    static void openExternalWindow(ScriptUI ui) {
        if (ui == null) return;
        ClassLoader uiClassLoader = ui.getClass().getClassLoader();
        EXTERNAL_UI_EXECUTOR.execute(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(uiClassLoader);
                ui.render();
            } finally {
                thread.setContextClassLoader(previous);
            }
        });
    }
}
