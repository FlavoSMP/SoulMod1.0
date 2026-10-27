package com.soulsmpmod;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

public final class SoulHeartManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("soulsmpmod");
    private static final Random RANDOM = new Random();
    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
    private static final List<DiceEffect> DICE_EFFECTS = List.of(
            new DiceEffect("Speed II", MobEffects.SPEED, 1, true),
            new DiceEffect("Strength II", MobEffects.STRENGTH, 1, true),
            new DiceEffect("Resistance II", MobEffects.RESISTANCE, 1, true),
            new DiceEffect("Weakness II", MobEffects.WEAKNESS, 1, false),
            new DiceEffect("Slowness II", MobEffects.SLOWNESS, 1, false),
            new DiceEffect("Mining Fatigue II", MobEffects.MINING_FATIGUE, 1, false)
    );

    private static Config config;
    private static Path dataFile;
    private static boolean initialized;
    private static int tickCounter;

    private SoulHeartManager() {
    }

    public static void initialize(Path dataDirectory, Config rules) {
        if (initialized) {
            return;
        }
        if (rules.startingHearts() < 1
                || rules.maximumHearts() < rules.startingHearts()
                || rules.ghostHearts() < 1
                || rules.diceThreshold() < 1
                || rules.strengthThreshold() < 1
                || rules.goldenHeartsThreshold() < 1
                || rules.speedThreshold() < 1
                || rules.effectDurationTicks() < 1
                || rules.effectDurationTicks() > 200) {
            throw new IllegalArgumentException("Invalid SoulHearts rules");
        }

        config = rules;
        dataFile = dataDirectory.resolve("player-hearts.properties").toAbsolutePath().normalize();
        loadPlayers();

        ServerLivingEntityEvents.AFTER_DEATH.register(SoulHeartManager::onDeath);
        ServerPlayerEvents.JOIN.register(SoulHeartManager::onJoin);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onJoin(newPlayer));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter % 20 != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                enforcePersistentEffects(player, stateFor(player));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        initialized = true;
        LOGGER.info("SoulHearts event handlers registered");
    }

    private static void loadPlayers() {
        Properties properties = new Properties();
        if (Files.isRegularFile(dataFile)) {
            try (InputStream input = Files.newInputStream(dataFile)) {
                properties.load(input);
            } catch (IOException exception) {
                LOGGER.error("Could not load player heart data", exception);
            }
        }

        for (String key : properties.stringPropertyNames()) {
            if (!key.endsWith(".hearts")) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key.substring(0, key.length() - ".hearts".length()));
                String prefix = uuid + ".";
                PlayerState state = new PlayerState(config.startingHearts());
                state.hearts = parseInt(properties.getProperty(prefix + "hearts"), config.startingHearts());
                state.ghost = Boolean.parseBoolean(properties.getProperty(prefix + "ghost", "false"));
                state.goldenHeartsGranted = Boolean.parseBoolean(
                        properties.getProperty(prefix + "goldenHeartsGranted", "false"));
                state.pendingEffect = parseInt(properties.getProperty(prefix + "pendingEffect"), -1);
                state.diceResolved = Boolean.parseBoolean(properties.getProperty(prefix + "diceResolved", "false"));
                PLAYERS.put(uuid, state);
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid player heart entry {}", key);
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void savePlayers() {
        Properties properties = new Properties();
        for (Map.Entry<UUID, PlayerState> entry : PLAYERS.entrySet()) {
            String prefix = entry.getKey() + ".";
            PlayerState state = entry.getValue();
            properties.setProperty(prefix + "hearts", Integer.toString(state.hearts));
            properties.setProperty(prefix + "ghost", Boolean.toString(state.ghost));
            properties.setProperty(prefix + "goldenHeartsGranted", Boolean.toString(state.goldenHeartsGranted));
            properties.setProperty(prefix + "pendingEffect", Integer.toString(state.pendingEffect));
            properties.setProperty(prefix + "diceResolved", Boolean.toString(state.diceResolved));
        }

        try {
            Files.createDirectories(dataFile.getParent());
            try (OutputStream output = Files.newOutputStream(dataFile)) {
                properties.store(output, "Soul SMP player heart data");
            }
        } catch (IOException exception) {
            LOGGER.error("Could not save player heart data", exception);
        }
    }

    private static PlayerState stateFor(ServerPlayer player) {
        return PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new PlayerState(config.startingHearts()));
    }

    private static void onJoin(ServerPlayer player) {
        PlayerState state = stateFor(player);
        if (state.ghost) {
            state.hearts = config.ghostHearts();
        }
        if (state.hearts == config.diceThreshold() && state.pendingEffect < 0 && !state.diceResolved) {
            rollDice(player, state);
        }
        applyPlayerState(player, state);
        savePlayers();
    }

    private static void onDeath(LivingEntity entity, DamageSource damageSource) {
        if (!(entity instanceof ServerPlayer victim)) {
            return;
        }

        PlayerState victimState = stateFor(victim);
        if (victimState.ghost) {
            return;
        }

        if (damageSource.getEntity() instanceof ServerPlayer killer && killer != victim) {
            PlayerState killerState = stateFor(killer);
            if (killerState.ghost) {
                killerState.ghost = false;
                killerState.hearts = 1;
                clearPendingDice(killerState);
                applyPlayerState(killer, killerState);
                killer.sendSystemMessage(Component.literal("You won the fight and returned as a human with 1 heart."));
            } else if (killerState.hearts < config.maximumHearts()) {
                int previousHearts = killerState.hearts;
                killerState.hearts++;
                onHeartsChanged(killer, killerState, previousHearts);
                killer.sendSystemMessage(Component.literal("You gained a heart. You now have "
                        + killerState.hearts + " hearts."));
            }
        }

        int previousHearts = victimState.hearts;
        if (previousHearts <= 1) {
            victimState.ghost = true;
            victimState.hearts = config.ghostHearts();
            clearPendingDice(victimState);
            victim.sendSystemMessage(Component.literal("You became a ghost. You respawn with blindness."));
        } else {
            victimState.hearts--;
            onHeartsChanged(victim, victimState, previousHearts);
            victim.sendSystemMessage(Component.literal("You lost a heart. You now have "
                    + victimState.hearts + " hearts."));
        }
        savePlayers();
    }

    private static void onHeartsChanged(ServerPlayer player, PlayerState state, int previousHearts) {
        if (state.hearts == config.diceThreshold() && previousHearts != config.diceThreshold()) {
            rollDice(player, state);
        } else if (state.hearts != config.diceThreshold()) {
            clearPendingDice(state);
        }
        applyPlayerState(player, state);
    }

    private static void rollDice(ServerPlayer player, PlayerState state) {
        state.pendingEffect = RANDOM.nextInt(DICE_EFFECTS.size());
        state.diceResolved = false;
        player.sendSystemMessage(Component.literal(
                "At 5 hearts, your roll is " + DICE_EFFECTS.get(state.pendingEffect).name()
                        + ". Use /soulheart use to accept it or /soulheart skip to decline."));
    }

    private static void clearPendingDice(PlayerState state) {
        state.pendingEffect = -1;
        state.diceResolved = false;
    }

    private static void applyPlayerState(ServerPlayer player, PlayerState state) {
        int hearts = state.ghost ? config.ghostHearts() : state.hearts;
        state.hearts = hearts;
        AttributeInstance maximumHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maximumHealth != null) {
            double maximum = hearts * 2.0;
            maximumHealth.setBaseValue(maximum);
            if (player.getHealth() > maximum) {
                player.setHealth((float) maximum);
            }
        }

        if (!state.goldenHeartsGranted && hearts >= config.goldenHeartsThreshold()) {
            state.goldenHeartsGranted = true;
            player.sendSystemMessage(Component.literal("You unlocked 8 permanent golden hearts."));
        }
        enforcePersistentEffects(player, state);
    }

    private static void enforcePersistentEffects(ServerPlayer player, PlayerState state) {
        if (state.ghost) {
            ensureEffect(player, MobEffects.BLINDNESS, 0);
        } else {
            player.removeEffect(MobEffects.BLINDNESS);
        }

        if (state.hearts >= config.strengthThreshold()) {
            ensureEffect(player, MobEffects.STRENGTH, 1);
        } else {
            player.removeEffect(MobEffects.STRENGTH);
        }

        if (state.hearts >= config.speedThreshold()) {
            ensureEffect(player, MobEffects.SPEED, 1);
        } else {
            player.removeEffect(MobEffects.SPEED);
        }

        if (state.goldenHeartsGranted) {
            ensureEffect(player, MobEffects.ABSORPTION, 3);
        }
    }

    private static void ensureEffect(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance current = player.getEffect(effect);
        if (current == null || current.getAmplifier() < amplifier || current.getDuration() < 1_200) {
            player.addEffect(new MobEffectInstance(effect, Integer.MAX_VALUE, amplifier));
        }
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("soulheart")
                .then(Commands.literal("use").executes(context -> useDiceEffect(
                        context.getSource().getPlayerOrException())))
                .then(Commands.literal("skip").executes(context -> skipDiceEffect(
                        context.getSource().getPlayerOrException())))
                .then(Commands.literal("status").executes(context -> showStatus(
                        context.getSource().getPlayerOrException()))));
    }

    private static int useDiceEffect(ServerPlayer player) {
        PlayerState state = stateFor(player);
        if (state.pendingEffect < 0 || state.pendingEffect >= DICE_EFFECTS.size()) {
            player.sendSystemMessage(Component.literal("You have no pending 5-heart effect."));
            return 0;
        }

        DiceEffect effect = DICE_EFFECTS.get(state.pendingEffect);
        player.addEffect(new MobEffectInstance(effect.effect(), config.effectDurationTicks(), effect.amplifier()));
        state.pendingEffect = -1;
        state.diceResolved = true;
        savePlayers();
        player.sendSystemMessage(Component.literal(effect.name() + " applied for up to 10 seconds."));
        return 1;
    }

    private static int skipDiceEffect(ServerPlayer player) {
        PlayerState state = stateFor(player);
        if (state.pendingEffect < 0) {
            player.sendSystemMessage(Component.literal("You have no pending 5-heart effect."));
            return 0;
        }

        state.pendingEffect = -1;
        state.diceResolved = true;
        savePlayers();
        player.sendSystemMessage(Component.literal("You declined your 5-heart effect."));
        return 1;
    }

    private static int showStatus(ServerPlayer player) {
        PlayerState state = stateFor(player);
        String identity = state.ghost ? "ghost" : "human";
        player.sendSystemMessage(Component.literal("SoulHearts: " + state.hearts + " hearts (" + identity + ")."));
        return 1;
    }

    public record Config(
            int startingHearts,
            int maximumHearts,
            int ghostHearts,
            int diceThreshold,
            int strengthThreshold,
            int goldenHeartsThreshold,
            int speedThreshold,
            int effectDurationTicks
    ) {
    }

    private static final class PlayerState {
        private int hearts;
        private boolean ghost;
        private boolean goldenHeartsGranted;
        private int pendingEffect = -1;
        private boolean diceResolved;

        private PlayerState(int hearts) {
            this.hearts = hearts;
        }
    }

    private record DiceEffect(String name, Holder<MobEffect> effect, int amplifier, boolean beneficial) {
    }
}