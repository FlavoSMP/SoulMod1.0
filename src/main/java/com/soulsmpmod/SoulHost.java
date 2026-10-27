package com.soulsmpmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SoulHost implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("soulsmpmod");

    private final Path scriptsDirectory = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("soulsmpmod")
            .resolve("scripts")
            .toAbsolutePath()
            .normalize();

    private Scriptable scope;

    @Override
    public void onInitialize() {
        try {
            Files.createDirectories(scriptsDirectory);
            installDefaultScript("SoulHost.js");
            installDefaultScript("SoulHearts.js");

            Context context = Context.enter();
            try {
                context.setOptimizationLevel(-1);
                scope = context.initStandardObjects();
                ScriptableObject.putProperty(
                        scope,
                        "SoulHostRuntime",
                        Context.javaToJS(new RuntimeBridge(this), scope)
                );
                executeScript(scriptsDirectory.resolve("SoulHost.js"));
            } finally {
                Context.exit();
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Failed to start Soul SMP scripts", exception);
        }
    }

    private void installDefaultScript(String fileName) throws IOException {
        Path destination = scriptsDirectory.resolve(fileName);
        if (Files.exists(destination)) {
            return;
        }

        try (InputStream script = getClass().getResourceAsStream("/scripts/" + fileName)) {
            if (script == null) {
                throw new IOException("Missing bundled script: " + fileName);
            }
            Files.copy(script, destination);
        }
    }

    private void loadScript(String fileName) throws IOException {
        if (!fileName.matches("[A-Za-z0-9_-]+\\.js")) {
            throw new IllegalArgumentException("Script name must be a simple .js filename");
        }

        Path script = scriptsDirectory.resolve(fileName).normalize();
        if (!script.startsWith(scriptsDirectory)) {
            throw new IllegalArgumentException("Script must be inside the scripts directory");
        }
        if (!Files.isRegularFile(script)) {
            throw new IOException("Script not found: " + fileName);
        }
        executeScript(script);
    }

    private void executeScript(Path script) throws IOException {
        boolean enteredContext = Context.getCurrentContext() == null;
        Context context = enteredContext ? Context.enter() : Context.getCurrentContext();
        try {
            context.setOptimizationLevel(-1);
            context.evaluateString(scope, Files.readString(script), script.toString(), 1, null);
            LOGGER.info("Loaded script {}", script.getFileName());
        } finally {
            if (enteredContext) {
                Context.exit();
            }
        }
    }

    public static final class RuntimeBridge {
        private final SoulHost host;

        private RuntimeBridge(SoulHost host) {
            this.host = host;
        }

        public void loadScript(String fileName) {
            try {
                host.loadScript(fileName);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        public void log(String message) {
            LOGGER.info("[Script] {}", message);
        }
    }
}