package dev.beamphysics.client;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.entity.DetachedPartEntity;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.net.Net;
import dev.beamphysics.physics.DamageSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-driving smoke test, only active with {@code -Dbeamphysics.autotest=true} (used by CI).
 * Creates a flat world, spawns the Test SUV, gets in, drives over a ledge into a wall, cycles
 * the cameras, gets out, saves screenshots and a pass/fail report, then closes the game.
 */
public final class AutoTest {
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static int phase;
    private static int ticks;
    private static int total;
    private static VehicleEntity car;
    private static double maxSpeed;
    private static boolean done;
    private static final int GROUND = -60;

    private AutoTest() {
    }

    public static boolean enabled() { return Boolean.getBoolean("beamphysics.autotest"); }

    public static void register() {
        BeamPhysics.LOG.info("[autotest] enabled");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (done) return;
            try {
                tick(client);
            } catch (Throwable t) {
                BeamPhysics.LOG.error("[autotest] crashed", t);
                check(false, "autotest threw " + t);
                finish(client);
            }
        });
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        REPORT.add(line);
        if (!ok) failures++;
        BeamPhysics.LOG.info("[autotest] {}", line);
    }

    private static void next() {
        phase++;
        ticks = 0;
    }

    private static void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png", client.getFramebuffer(), text -> {
        });
    }

    private static void look(MinecraftClient client, float yaw, float pitch) {
        client.player.setYaw(yaw);
        client.player.prevYaw = yaw;
        client.player.setPitch(pitch);
        client.player.prevPitch = pitch;
    }

    private static void finish(MinecraftClient client) {
        done = true;
        REPORT.add(failures == 0 ? "RESULT: ALL PASSED" : "RESULT: " + failures + " FAILED");
        try {
            Files.write(new File(client.runDirectory, "autotest-report.txt").toPath(), REPORT, StandardCharsets.UTF_8);
        } catch (Exception e) {
            BeamPhysics.LOG.error("[autotest] could not write report", e);
        }
        client.options.forwardKey.setPressed(false);
        client.scheduleStop();
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        if (++total > 20 * 60 * 6) {
            check(false, "timed out in phase " + phase);
            finish(client);
            return;
        }
        client.options.pauseOnLostFocus = false;
        switch (phase) {
            case 0 -> {
                if (client.currentScreen instanceof TitleScreen && ticks > 40) {
                    check(true, "game reached the title screen with the mod loaded");
                    LevelInfo info = new LevelInfo("beamphysics_autotest", GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), DataConfiguration.SAFE_MODE);
                    client.createIntegratedServerLoader().createAndStart("beamphysics_autotest", info,
                            new GeneratorOptions(1234L, false, false),
                            registries -> registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.FLAT).value().createDimensionsRegistryHolder());
                    next();
                }
            }
            case 1 -> {
                if (client.player != null && client.world != null && client.currentScreen == null && ticks > 100) {
                    check(true, "singleplayer world started");
                    IntegratedServer server = client.getServer();
                    server.execute(() -> {
                        ServerWorld world = server.getOverworld();
                        world.setTimeOfDay(6000);
                        world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
                        world.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
                        ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                        player.teleport(world, 4.5, GROUND, 4.5, 0, 0);
                        // a one-block ledge to climb, then a wall to hit
                        for (int x = -5; x <= 5; x++) {
                            for (int z = 28; z <= 40; z++) world.setBlockState(new BlockPos(x, GROUND, z), Blocks.STONE.getDefaultState());
                            for (int z = 78; z <= 80; z++) {
                                for (int y = GROUND; y < GROUND + 5; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.STONE_BRICKS.getDefaultState());
                            }
                        }
                        VehicleEntity v = VehicleEntity.spawn(world, "test_suv", 0.5, GROUND, 8.5, 0);
                        v.tryEnter(player);
                    });
                    next();
                }
            }
            case 2 -> {
                if (ticks < 40) return;
                Entity vehicle = client.player.getVehicle();
                check(vehicle instanceof VehicleEntity, "player is seated in the vehicle on the client");
                if (!(vehicle instanceof VehicleEntity v)) {
                    finish(client);
                    return;
                }
                car = v;
                check(car.snapshot != null, "client receives physics state packets");
                check(car.vehicleType().model().totalQuads() > 500, "vehicle model loaded (" + car.vehicleType().model().totalQuads() + " quads)");
                if (car.snapshot != null) {
                    int grounded = 0;
                    for (boolean c : car.snapshot.contact) if (c) grounded++;
                    check(grounded == 4, "all four wheels found the ground (" + grounded + ")");
                }
                VehicleCamera.mode = VehicleCamera.Mode.THIRD_PERSON;
                next();
            }
            case 3 -> {
                look(client, 145, 12);
                if (ticks == 20) shot(client, "01_spawned_third_person");
                if (ticks == 30) {
                    look(client, -40, 18);
                }
                if (ticks > 30) look(client, -40, 18);
                if (ticks == 50) shot(client, "02_rear_three_quarter");
                if (ticks == 55) {
                    ClientInput.sendAction(Net.ACTION_ENGINE, car.getId());
                    VehicleCamera.mode = VehicleCamera.Mode.INTERIOR;
                    next();
                }
            }
            case 4 -> {
                look(client, car.getYaw(), 6);
                if (ticks == 40) {
                    check(car.snapshot.engineRunning, "engine starts on the R action (rpm " + Math.round(car.snapshot.rpm) + ")");
                    shot(client, "03_interior_first_person");
                    VehicleCamera.mode = VehicleCamera.Mode.CHASE;
                    client.options.forwardKey.setPressed(true);
                    ClientState.debug = true;
                    next();
                }
            }
            case 5 -> {
                maxSpeed = Math.max(maxSpeed, car.snapshot.velocity.length());
                double z = car.getZ();
                if (z > 25.5 && z < 27.5) shot(client, "04_climbing_ledge_debug");
                if (z > 44 || ticks > 400) {
                    check(z > 44, "drove over the one-block ledge (z=" + String.format("%.1f", z) + ")");
                    check(car.getY() > GROUND - 0.5, "did not fall through the ground");
                    ClientState.debug = false;
                    shot(client, "05_chase_camera");
                    next();
                }
            }
            case 6 -> {
                maxSpeed = Math.max(maxSpeed, car.snapshot.velocity.length());
                DamageSystem d = car.physics().damage;
                boolean crashed = car.clientDamageVersion > 0 && d.zones[DamageSystem.Z_FRONT_CENTER] + d.zones[0] + d.zones[2] > 0.05;
                if (crashed || ticks > 500) {
                    client.options.forwardKey.setPressed(false);
                    check(maxSpeed > 8, "accelerated under throttle (max " + String.format("%.0f", maxSpeed * 3.6) + " km/h)");
                    check(crashed, "wall impact produced front damage on the client");
                    next();
                }
            }
            case 7 -> {
                if (ticks == 50) {
                    DamageSystem d = car.physics().damage;
                    float maxDent = 0;
                    for (float f : d.lattice) maxDent = Math.max(maxDent, Math.abs(f));
                    check(maxDent > 0.1, "body geometry is deformed (max dent " + String.format("%.2f", maxDent) + " m)");
                    int parts = 0;
                    for (Entity e : client.world.getEntities()) if (e instanceof DetachedPartEntity) parts++;
                    check(d.detachedMask() != 0 && parts > 0, "parts were torn off (" + parts + " loose part entities)");
                    check(d.zones[DamageSystem.Z_REAR_CENTER] < 0.05, "rear of the car is undamaged");
                    shot(client, "06_after_crash_chase");
                    VehicleCamera.mode = VehicleCamera.Mode.THIRD_PERSON;
                }
                if (ticks > 50) look(client, 150, 14);
                if (ticks == 70) shot(client, "07_crash_damage_front");
                if (ticks > 75) look(client, -120, 10);
                if (ticks == 95) {
                    shot(client, "08_crash_damage_side");
                    VehicleCamera.mode = VehicleCamera.Mode.HOOD;
                }
                if (ticks > 95) look(client, car.getYaw(), 10);
                if (ticks == 115) {
                    shot(client, "09_hood_camera");
                    ClientInput.sendAction(Net.ACTION_EXIT, car.getId());
                    next();
                }
            }
            case 8 -> {
                if (ticks == 30) {
                    check(client.player.getVehicle() == null, "player can leave the vehicle");
                    check(!car.isRemoved(), "vehicle stays in the world after the driver leaves");
                    client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, car.getPos().add(0, 0.8, 0));
                }
                if (ticks == 50) {
                    shot(client, "10_on_foot_after_exit");
                    finish(client);
                }
            }
            default -> {
            }
        }
    }
}
