package dev.beamphysics;

import dev.beamphysics.command.VehicleCommand;
import dev.beamphysics.entity.DetachedPartEntity;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.item.VehicleSpawnItem;
import dev.beamphysics.net.Net;
import dev.beamphysics.world.SurfaceFriction;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeamPhysics implements ModInitializer {
    public static final String MOD_ID = "beamphysics";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static EntityType<VehicleEntity> VEHICLE;
    public static EntityType<DetachedPartEntity> DETACHED_PART;
    public static Item TEST_SUV_ITEM;
    public static SoundEvent ENGINE_SOUND;
    public static SoundEvent SKID_SOUND;

    public static Identifier id(String path) { return new Identifier(MOD_ID, path); }

    @Override
    public void onInitialize() {
        SurfaceFriction.load();
        VehicleRegistry.load();

        VEHICLE = Registry.register(Registries.ENTITY_TYPE, id("vehicle"),
                FabricEntityTypeBuilder.<VehicleEntity>create(SpawnGroup.MISC, VehicleEntity::new)
                        .dimensions(EntityDimensions.fixed(2.3f, 1.8f))
                        .trackRangeBlocks(192)
                        .trackedUpdateRate(2)
                        .forceTrackedVelocityUpdates(false)
                        .fireImmune()
                        .build());
        DETACHED_PART = Registry.register(Registries.ENTITY_TYPE, id("detached_part"),
                FabricEntityTypeBuilder.<DetachedPartEntity>create(SpawnGroup.MISC, DetachedPartEntity::new)
                        .dimensions(EntityDimensions.fixed(0.8f, 0.45f))
                        .trackRangeBlocks(128)
                        .trackedUpdateRate(1)
                        .forceTrackedVelocityUpdates(true)
                        .build());

        TEST_SUV_ITEM = Registry.register(Registries.ITEM, id("test_suv_spawn_egg"),
                new VehicleSpawnItem("test_suv", new Item.Settings().maxCount(1)));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(TEST_SUV_ITEM));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(TEST_SUV_ITEM));

        ENGINE_SOUND = Registry.register(Registries.SOUND_EVENT, id("engine"), SoundEvent.of(id("engine")));
        SKID_SOUND = Registry.register(Registries.SOUND_EVENT, id("skid"), SoundEvent.of(id("skid")));

        Net.registerServer();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> VehicleCommand.register(dispatcher));

        LOG.info("BeamPhysics loaded: {} vehicle type(s)", VehicleRegistry.ids().size());
    }
}
