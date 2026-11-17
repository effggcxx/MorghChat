package me.ehsan.morghchat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Plugin(id = "morghchatvelocity", name = "MorghChatVelocity", version = "0.1.0-SNAPSHOT",
        description = "TCP network bridge for MorghChat Paper backends", authors = {"Ehsan"})
public final class MorghChatVelocity {
    private final Logger logger;
    private final Path dataDirectory;
    private TcpBridgeServer bridge;

    @Inject
    public MorghChatVelocity(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            Properties config = loadConfig();
            String bind = config.getProperty("bind", "127.0.0.1").trim();
            int port = Integer.parseInt(config.getProperty("port", "25590").trim());
            String secret = config.getProperty("secret", "").trim();
            if (secret.length() < 32 || secret.startsWith("CHANGE_THIS")) {
                logger.error("[MorghChat] Set a secret of at least 32 characters in {}/config.properties. TCP bridge disabled.", dataDirectory);
                return;
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
            bridge = new TcpBridgeServer(logger, bind, port, secret);
            bridge.start();
            logger.info("[MorghChat] TCP bridge listening on {}:{}", bind, port);
        } catch (Exception exception) {
            logger.error("[MorghChat] Could not start TCP bridge", exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    private Properties loadConfig() throws IOException {
        Path file = dataDirectory.resolve("config.properties");
        if (Files.notExists(file)) {
            Properties defaults = new Properties();
            defaults.setProperty("bind", "127.0.0.1");
            defaults.setProperty("port", "25590");
            defaults.setProperty("secret", "CHANGE_THIS_TO_A_RANDOM_SECRET_AT_LEAST_32_CHARACTERS");
            try (var output = Files.newOutputStream(file)) {
                defaults.store(output, "MorghChat Velocity TCP bridge");
            }
            logger.warn("[MorghChat] Created {}. Change the default secret before allowing connections.", file);
            return defaults;
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }
}
