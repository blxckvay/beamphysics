package dev.beamphysics.client;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.entity.VehicleSnapshot;
import dev.beamphysics.net.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;

public class BeamPhysicsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(BeamPhysics.VEHICLE, VehicleRenderer::new);
        EntityRendererRegistry.register(BeamPhysics.DETACHED_PART, DetachedPartRenderer::new);
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> tintIndex == 0 ? 0xD4541C : 0x26282C, BeamPhysics.TEST_SUV_ITEM);

        ClientInput.register();
        HudRenderCallback.EVENT.register(VehicleHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientInput.tick(client);
            VehicleSounds.tick(client);
        });

        ClientPlayNetworking.registerGlobalReceiver(Net.STATE, (client, handler, buf, responseSender) -> {
            int id = Net.readEntityId(buf);
            VehicleSnapshot snapshot = Net.readState(buf);
            client.execute(() -> {
                if (client.world == null) return;
                Entity e = client.world.getEntityById(id);
                if (e instanceof VehicleEntity v) v.acceptSnapshot(snapshot);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.DAMAGE, (client, handler, buf, responseSender) -> {
            int id = Net.readEntityId(buf);
            PacketByteBuf copy = new PacketByteBuf(buf.copy());
            client.execute(() -> {
                try {
                    if (client.world == null) return;
                    Entity e = client.world.getEntityById(id);
                    if (e instanceof VehicleEntity v) {
                        Net.readDamage(copy, v.physics().damage, v.config());
                        v.clientDamageVersion++;
                    }
                } finally {
                    copy.release();
                }
            });
        });
    }
}
