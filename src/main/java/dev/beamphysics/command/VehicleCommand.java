package dev.beamphysics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.beamphysics.VehicleRegistry;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.world.SurfaceFriction;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/** /vehicle spawn &lt;id&gt; | list | repair | flip | remove | reload */
public final class VehicleCommand {
    private VehicleCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("vehicle")
                .then(CommandManager.literal("spawn").requires(s -> s.hasPermissionLevel(2))
                        .executes(ctx -> spawn(ctx.getSource(), VehicleRegistry.DEFAULT))
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .suggests((ctx, builder) -> CommandSource.suggestMatching(VehicleRegistry.ids(), builder))
                                .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(CommandManager.literal("list").executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Vehicles: " + String.join(", ", VehicleRegistry.ids())), false);
                    return 1;
                }))
                .then(CommandManager.literal("repair").executes(ctx -> withVehicle(ctx.getSource(), v -> {
                    v.repair();
                    return "message.beamphysics.repaired";
                })))
                .then(CommandManager.literal("flip").executes(ctx -> withVehicle(ctx.getSource(), v -> {
                    v.flipUpright();
                    return "message.beamphysics.flipped";
                })))
                .then(CommandManager.literal("remove").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> withVehicle(ctx.getSource(), v -> {
                    v.discard();
                    return "message.beamphysics.removed";
                })))
                .then(CommandManager.literal("reload").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                    SurfaceFriction.load();
                    ctx.getSource().sendFeedback(() -> Text.translatable("message.beamphysics.reloaded"), false);
                    return 1;
                })));
    }

    private static int spawn(ServerCommandSource source, String id) {
        if (!VehicleRegistry.exists(id)) {
            source.sendError(Text.translatable("message.beamphysics.unknown_vehicle", id));
            return 0;
        }
        Vec3d pos = source.getPosition();
        float yaw = source.getRotation().y;
        // a little in front of the caller so it does not land on their head
        double rad = Math.toRadians(yaw);
        double x = pos.x - Math.sin(rad) * 4.0, z = pos.z + Math.cos(rad) * 4.0;
        VehicleEntity.spawn(source.getWorld(), id, x, pos.y + 0.6, z, yaw);
        source.sendFeedback(() -> Text.translatable("message.beamphysics.spawned", id), true);
        return 1;
    }

    private interface Action {
        String run(VehicleEntity v);
    }

    private static int withVehicle(ServerCommandSource source, Action action) {
        VehicleEntity target = null;
        Entity self = source.getEntity();
        if (self != null && self.getVehicle() instanceof VehicleEntity riding) {
            target = riding;
        } else {
            Vec3d pos = source.getPosition();
            List<VehicleEntity> near = source.getWorld().getEntitiesByClass(VehicleEntity.class,
                    new net.minecraft.util.math.Box(pos.subtract(12, 12, 12), pos.add(12, 12, 12)), e -> true);
            target = near.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(pos))).orElse(null);
        }
        if (target == null) {
            source.sendError(Text.translatable("message.beamphysics.no_vehicle"));
            return 0;
        }
        String key = action.run(target);
        source.sendFeedback(() -> Text.translatable(key), false);
        return 1;
    }
}
