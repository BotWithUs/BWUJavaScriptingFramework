package com.botwithus.bot.cli.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistent CLI configuration stored at ~/.botwithus/config.properties.
 */
public class CliConfig {

    private static final Logger log = LoggerFactory.getLogger(CliConfig.class);
    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".botwithus");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");

    private final Properties props = new Properties();

    public String getAutoConnectPipes() { return props.getProperty("autoConnectPipes", ""); }
    public void setAutoConnectPipes(String pipes) { props.setProperty("autoConnectPipes", pipes); }

    public long getDefaultTimeout() {
        try { return Long.parseLong(props.getProperty("defaultTimeout", "10000")); }
        catch (NumberFormatException e) { return 10000; }
    }
    public void setDefaultTimeout(long ms) { props.setProperty("defaultTimeout", String.valueOf(ms)); }

    public boolean isAutoReload() { return Boolean.parseBoolean(props.getProperty("autoReload", "false")); }
    public void setAutoReload(boolean enabled) { props.setProperty("autoReload", String.valueOf(enabled)); }

    public String get(String key) { return props.getProperty(key); }
    public void set(String key, String value) { props.setProperty(key, value); }

    public Properties getProperties() { return props; }

    public void load() {
        if (!Files.exists(CONFIG_FILE)) return;
        try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
            props.load(in);
        } catch (IOException e) {
            log.error("Failed to load config", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "JBotWithUs CLI Configuration");
            }
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    public static CliConfig defaults() {
        CliConfig config = new CliConfig();
        config.load();
        return config;
    }
}
