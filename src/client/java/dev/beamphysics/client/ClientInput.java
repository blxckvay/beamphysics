package dev.beamphysics.client;

import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.net.Net;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Keyboard handling. The vehicle keys deliberately overlap vanilla ones (F, Q, E), so they are
 * polled directly and the vanilla action behind the same key is suppressed while it would
 * conflict (see KeyBindingMixin).
 */
public final class ClientInput {
    public static KeyBinding KEY_ENTER, KEY_ENGINE, KEY_CAMERA, KEY_SHIFT_DOWN, KEY_SHIFT_UP, KEY_DEBUG, KEY_AUTO;
    private static KeyBinding[] all;
    private static boolean[] wasDown;
    private static float lastCarYaw;
    private static boolean hadVehicle;
    private static VehicleEntity nearest;

    private ClientInput() {
    }

    public static void register() {
        String cat = "category.beamphysics";
        KEY_ENTER = reg("key.beamphysics.enter", GLFW.GLFW_KEY_F, cat);
        KEY_ENGINE = reg("key.beamphysics.engine", GLFW.GLFW_KEY_R, cat);
        KEY_CAMERA = reg("key.beamphysics.camera", GLFW.GLFW_KEY_C, cat);
        KEY_SHIFT_DOWN = reg("key.beamphysics.shift_down", GLFW.GLFW_KEY_Q, cat);
        KEY_SHIFT_UP = reg("key.beamphysics.shift_up", GLFW.GLFW_KEY_E, cat);
        KEY_DEBUG = reg("key.beamphysics.debug", GLFW.GLFW_KEY_F8, cat);
        KEY_AUTO = reg("key.beamphysics.auto", GLFW.GLFW_KEY_G, cat);
        all = new KeyBinding[]{KEY_ENTER, KEY_ENGINE, KEY_CAMERA, KEY_SHIFT_DOWN, KEY_SHIFT_UP, KEY_DEBUG, KEY_AUTO};
        wasDown = new boolean[all.length];
    }

    private static KeyBinding reg(String name, int key, String category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, key, category));
    }

    private static int code(KeyBinding kb) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
        return key.getCategory() == InputUtil.Type.KEYSYM ? key.getCode() : -1;
    }

    /** Should the vanilla binding sitting on this key be skipped right now? */
    public static boolean shouldCapture(InputUtil.Key key) {
        if (all == null || key.getCategory() != InputUtil.Type.KEYSYM) return false;
        int c = key.getCode();
        if (ClientState.riding) {
            for (KeyBinding kb : all) if (code(kb) == c) return true;
            return false;
        }
        return ClientState.nearVehicle && c == code(KEY_ENTER);
    }

    private static boolean pressed(MinecraftClient client, int index) {
        int c = code(all[index]);
        boolean down = c > 0 && client.currentScreen == null
                && InputUtil.isKeyPressed(client.getWindow().getHandle(), c);
        boolean edge = down && !wasDown[index];
        wasDown[index] = down;
        return edge;
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            ClientState.riding = false;
            ClientState.nearVehicle = false;
            VehicleCamera.tick(client, false);
            return;
        }
        VehicleEntity vehicle = player.getVehicle() instanceof VehicleEntity v ? v : null;
        ClientState.riding = vehicle != null;
        VehicleCamera.tick(client, vehicle != null);

        nearest = null;
        if (vehicle == null) {
            if (client.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof VehicleEntity v
                    && player.squaredDistanceTo(v) < 36) {
                nearest = v;
            } else {
                double best = 4.5 * 4.5;
                for (Entity e : client.world.getEntities()) {
                    if (e instanceof VehicleEntity v) {
                        double d = player.squaredDistanceTo(v.getX(), v.getY() + 0.8, v.getZ());
                        if (d < best) {
                            best = d;
                            nearest = v;
                        }
                    }
                }
            }
        }
        ClientState.nearVehicle = nearest != null;

        boolean enter = pressed(client, 0), engine = pressed(client, 1), camera = pressed(client, 2);
        boolean down = pressed(client, 3), up = pressed(client, 4), debug = pressed(client, 5), auto = pressed(client, 6);

        if (debug) {
            ClientState.debug = !ClientState.debug;
            player.sendMessage(Text.translatable(ClientState.debug ? "message.beamphysics.debug_on" : "message.beamphysics.debug_off"), true);
        }

        if (vehicle == null) {
            hadVehicle = false;
            if (enter && nearest != null) sendAction(Net.ACTION_ENTER, nearest.getId());
            return;
        }

        // keep the player's view turning with the car
        float carYaw = vehicle.getYaw();
        if (hadVehicle) {
            float delta = MathHelper.wrapDegrees(carYaw - lastCarYaw);
            player.setYaw(player.getYaw() + delta);
            player.prevYaw += delta;
        } else {
            player.setYaw(carYaw);
            player.prevYaw = carYaw;
            player.setPitch(8);
            hadVehicle = true;
        }
        lastCarYaw = carYaw;

        if (enter) {
            sendAction(Net.ACTION_EXIT, vehicle.getId());
            return;
        }
        if (camera) {
            VehicleCamera.cycle();
            player.sendMessage(Text.translatable(VehicleCamera.mode.key), true);
        }
        if (!vehicle.isDriver(player)) return;
        if (engine) sendAction(Net.ACTION_ENGINE, vehicle.getId());
        if (up) sendAction(Net.ACTION_SHIFT_UP, vehicle.getId());
        if (down) sendAction(Net.ACTION_SHIFT_DOWN, vehicle.getId());
        if (auto) sendAction(Net.ACTION_TOGGLE_AUTO, vehicle.getId());

        boolean free = client.currentScreen == null;
        float forward = free && client.options.forwardKey.isPressed() ? 1 : 0;
        float backward = free && client.options.backKey.isPressed() ? 1 : 0;
        float steer = 0;
        if (free && client.options.leftKey.isPressed()) steer += 1;
        if (free && client.options.rightKey.isPressed()) steer -= 1;
        boolean handbrake = free && client.options.jumpKey.isPressed();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(forward);
        buf.writeFloat(backward);
        buf.writeFloat(steer);
        buf.writeBoolean(handbrake);
        ClientPlayNetworking.send(Net.INPUT, buf);
    }

    public static void sendAction(int action, int entityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(action);
        buf.writeVarInt(entityId);
        ClientPlayNetworking.send(Net.ACTION, buf);
    }
}
