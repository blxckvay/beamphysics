package dev.beamphysics.item;

import dev.beamphysics.entity.VehicleEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Spawn-egg style item: right click a block to place the vehicle on it, facing the way you look. */
public class VehicleSpawnItem extends Item {
    private final String vehicleType;

    public VehicleSpawnItem(String vehicleType, Settings settings) {
        super(settings);
        this.vehicleType = vehicleType;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (world.isClient) return ActionResult.SUCCESS;
        Vec3d hit = context.getHitPos();
        float yaw = context.getPlayer() != null ? context.getPlayer().getYaw() : 0;
        double y = context.getBlockPos().offset(context.getSide()).getY();
        if (context.getSide() == net.minecraft.util.math.Direction.UP) y = hit.y;
        VehicleEntity.spawn((ServerWorld) world, vehicleType, hit.x, y, hit.z, yaw);
        world.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.NEUTRAL, 0.5f, 1.2f);
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().creativeMode) {
            context.getStack().decrement(1);
        }
        return ActionResult.CONSUME;
    }
}
