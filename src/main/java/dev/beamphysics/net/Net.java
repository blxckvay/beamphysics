package dev.beamphysics.net;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.entity.VehicleSnapshot;
import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.VehiclePhysics;
import dev.beamphysics.physics.WheelPhysics;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Packet ids and codecs. The server owns physics and damage; clients only receive state for
 * rendering and send driver input back.
 */
public final class Net {
    public static final Identifier STATE = new Identifier(BeamPhysics.MOD_ID, "state");
    public static final Identifier DAMAGE = new Identifier(BeamPhysics.MOD_ID, "damage");
    public static final Identifier INPUT = new Identifier(BeamPhysics.MOD_ID, "input");
    public static final Identifier ACTION = new Identifier(BeamPhysics.MOD_ID, "action");

    public static final int ACTION_ENGINE = 0, ACTION_SHIFT_UP = 1, ACTION_SHIFT_DOWN = 2, ACTION_TOGGLE_AUTO = 3,
            ACTION_ENTER = 4, ACTION_EXIT = 5;

    private Net() {
    }

    // ------------------------------------------------------------------ server -> client

    public static PacketByteBuf writeState(VehicleEntity entity, VehiclePhysics p) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entity.getId());
        V3 o = p.origin();
        buf.writeDouble(o.x);
        buf.writeDouble(o.y);
        buf.writeDouble(o.z);
        Quat q = p.rotation;
        buf.writeFloat((float) q.w);
        buf.writeFloat((float) q.x);
        buf.writeFloat((float) q.y);
        buf.writeFloat((float) q.z);
        writeV3f(buf, p.linearVelocity);
        writeV3f(buf, p.angularVelocity);
        for (int i = 0; i < 4; i++) {
            WheelPhysics w = p.wheels[i];
            buf.writeFloat((float) w.compression);
            buf.writeFloat((float) w.spinAngle);
            buf.writeFloat((float) w.steeringAngle);
            buf.writeFloat((float) w.slipRatio);
            buf.writeFloat((float) w.slipAngle);
            buf.writeBoolean(w.contact);
            buf.writeFloat((float) w.contactPoint.x);
            buf.writeFloat((float) w.contactPoint.y);
            buf.writeFloat((float) w.contactPoint.z);
        }
        buf.writeFloat((float) p.steer);
        buf.writeFloat((float) p.engine.rpm());
        buf.writeFloat((float) p.engine.throttle);
        buf.writeFloat((float) p.forwardSpeed());
        buf.writeFloat((float) p.damage.temperature);
        buf.writeByte(p.transmission.gear);
        int flags = (p.engine.running ? 1 : 0) | (p.engine.starter > 0 ? 2 : 0) | (p.transmission.automatic ? 4 : 0)
                | (p.handbrake ? 8 : 0) | (p.resting ? 16 : 0);
        buf.writeByte(flags);
        return buf;
    }

    public static int readEntityId(PacketByteBuf buf) { return buf.readVarInt(); }

    public static VehicleSnapshot readState(PacketByteBuf buf) {
        VehicleSnapshot s = new VehicleSnapshot();
        s.position = new V3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float w = buf.readFloat(), x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        s.rotation = new Quat(w, x, y, z).normalize();
        s.velocity = readV3f(buf);
        s.angularVelocity = readV3f(buf);
        for (int i = 0; i < 4; i++) {
            s.compression[i] = buf.readFloat();
            s.spin[i] = buf.readFloat();
            s.wheelSteer[i] = buf.readFloat();
            s.slipRatio[i] = buf.readFloat();
            s.slipAngle[i] = buf.readFloat();
            s.contact[i] = buf.readBoolean();
            s.contactPoint[i][0] = buf.readFloat();
            s.contactPoint[i][1] = buf.readFloat();
            s.contactPoint[i][2] = buf.readFloat();
        }
        s.steer = buf.readFloat();
        s.rpm = buf.readFloat();
        s.throttle = buf.readFloat();
        s.forwardSpeed = buf.readFloat();
        s.temperature = buf.readFloat();
        s.gear = buf.readByte();
        int flags = buf.readByte();
        s.engineRunning = (flags & 1) != 0;
        s.starting = (flags & 2) != 0;
        s.automatic = (flags & 4) != 0;
        s.handbrake = (flags & 8) != 0;
        s.resting = (flags & 16) != 0;
        return s;
    }

    public static PacketByteBuf writeDamage(VehicleEntity entity, DamageSystem d) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entity.getId());
        buf.writeByteArray(d.latticeBytes());
        buf.writeInt(d.detachedMask());
        buf.writeVarInt(d.partCount());
        for (int i = 0; i < d.partCount(); i++) buf.writeByte((int) Math.round(d.partLooseness(i) * 100));
        for (int i = 0; i < 4; i++) {
            buf.writeFloat((float) d.wheelCamber[i]);
            buf.writeFloat((float) d.wheelToe[i]);
        }
        buf.writeFloat((float) d.engineHealth);
        buf.writeFloat((float) d.radiatorHealth);
        buf.writeFloat((float) d.coolant);
        buf.writeVarInt(DamageSystem.ZONE_COUNT);
        for (int i = 0; i < DamageSystem.ZONE_COUNT; i++) buf.writeByte((int) Math.round(d.zones[i] * 100));
        return buf;
    }

    /** Applies a damage packet (after the entity id) to a client-side damage model. */
    public static void readDamage(PacketByteBuf buf, DamageSystem d, dev.beamphysics.physics.VehicleConfig cfg) {
        d.setLatticeBytes(buf.readByteArray());
        d.setDetachedMask(buf.readInt());
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            double loose = buf.readByte() / 100.0;
            if (i < d.partCount()) d.partHealth[i] = cfg.damage.parts[i].health * (1 - loose);
        }
        for (int i = 0; i < 4; i++) {
            d.wheelCamber[i] = buf.readFloat();
            d.wheelToe[i] = buf.readFloat();
        }
        d.engineHealth = buf.readFloat();
        d.radiatorHealth = buf.readFloat();
        d.coolant = buf.readFloat();
        int zones = buf.readVarInt();
        for (int i = 0; i < zones; i++) {
            double v = buf.readByte() / 100.0;
            if (i < DamageSystem.ZONE_COUNT) d.zones[i] = v;
        }
        d.stateVersion++;
    }

    private static void writeV3f(PacketByteBuf buf, V3 v) {
        buf.writeFloat((float) v.x);
        buf.writeFloat((float) v.y);
        buf.writeFloat((float) v.z);
    }

    private static V3 readV3f(PacketByteBuf buf) {
        return new V3(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    // ------------------------------------------------------------------ client -> server

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(INPUT, (server, player, handler, buf, responseSender) -> {
            float forward = buf.readFloat(), backward = buf.readFloat(), steer = buf.readFloat();
            boolean handbrake = buf.readBoolean();
            server.execute(() -> {
                if (player.getVehicle() instanceof VehicleEntity v && v.isDriver(player)) {
                    v.setDriverInput(clamp01(forward), clamp01(backward), Math.max(-1, Math.min(1, steer)), handbrake);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
            int action = buf.readByte();
            int target = buf.readVarInt();
            server.execute(() -> handleAction(player, action, target));
        });
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }

    private static void handleAction(ServerPlayerEntity player, int action, int target) {
        if (action == ACTION_ENTER) {
            Entity e = player.getWorld().getEntityById(target);
            if (e instanceof VehicleEntity v && player.getVehicle() == null && player.squaredDistanceTo(v) < 49) {
                v.tryEnter(player);
            }
            return;
        }
        if (!(player.getVehicle() instanceof VehicleEntity v)) return;
        if (action == ACTION_EXIT) {
            player.stopRiding();
            return;
        }
        if (!v.isDriver(player)) return;
        v.driverAction(action, player);
    }
}
