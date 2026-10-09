package dev.beamphysics.entity;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A body panel or wheel that has been torn off. Created only at the moment of separation;
 * a lightweight tumbling object with gravity, bounce and friction.
 */
public class DetachedPartEntity extends Entity {
    private static final TrackedData<NbtCompound> DATA = DataTracker.registerData(DetachedPartEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final int LIFETIME = 20 * 60 * 5;

    private int life;
    // client-side tumble
    public float tumble, prevTumble;
    private boolean settled;

    public DetachedPartEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    public static void spawn(ServerWorld world, String vehicleType, String part, int wheel, V3 pos, V3 velocity, Quat rotation, byte[] lattice) {
        DetachedPartEntity e = new DetachedPartEntity(BeamPhysics.DETACHED_PART, world);
        NbtCompound data = new NbtCompound();
        data.putString("Vehicle", vehicleType);
        data.putString("Part", part);
        data.putInt("Wheel", wheel);
        data.putFloat("Qw", (float) rotation.w);
        data.putFloat("Qx", (float) rotation.x);
        data.putFloat("Qy", (float) rotation.y);
        data.putFloat("Qz", (float) rotation.z);
        if (wheel < 0) data.putByteArray("Lattice", lattice);
        e.dataTracker.set(DATA, data);
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);
        double max = 2.5;
        e.setVelocity(clamp(velocity.x / 20.0, max), clamp(velocity.y / 20.0, max), clamp(velocity.z / 20.0, max));
        world.spawnEntity(e);
    }

    private static double clamp(double v, double max) { return Math.max(-max, Math.min(max, v)); }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(DATA, new NbtCompound());
    }

    public NbtCompound data() { return this.dataTracker.get(DATA); }

    public String vehicleTypeId() { return data().getString("Vehicle"); }

    public String partName() { return data().getString("Part"); }

    public boolean isWheel() { return data().getInt("Wheel") >= 0; }

    public Quat baseRotation() {
        NbtCompound d = data();
        if (!d.contains("Qw")) return Quat.IDENTITY;
        return new Quat(d.getFloat("Qw"), d.getFloat("Qx"), d.getFloat("Qy"), d.getFloat("Qz")).normalize();
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            prevTumble = tumble;
            double dx = getX() - prevX, dy = getY() - prevY, dz = getZ() - prevZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved > 0.02) {
                tumble += (float) (moved * (isWheel() ? 115 : 70));
                settled = false;
            } else if (!settled && !isWheel()) {
                // come to rest lying flat
                float target = Math.round(tumble / 180f) * 180f;
                tumble += (target - tumble) * 0.35f;
                if (Math.abs(target - tumble) < 0.5f) {
                    tumble = target;
                    settled = true;
                }
            }
            return;
        }

        Vec3d v = getVelocity();
        if (!hasNoGravity()) v = v.add(0, -0.05, 0);
        boolean wheel = isWheel();
        move(MovementType.SELF, v);
        if (horizontalCollision) {
            v = new Vec3d(v.x * -0.3, v.y, v.z * -0.3);
        }
        if (verticalCollision) {
            double hf = wheel ? 0.93 : 0.62;
            if (v.y < -0.18) {
                if (v.y < -0.35) {
                    getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.NEUTRAL, 0.25f, wheel ? 0.6f : 1.5f);
                }
                v = new Vec3d(v.x * hf, -v.y * (wheel ? 0.5 : 0.3), v.z * hf);
            } else {
                v = new Vec3d(v.x * hf, 0, v.z * hf);
            }
        }
        if (v.lengthSquared() < 1e-5) v = Vec3d.ZERO;
        setVelocity(v.multiply(0.99));
        if (isTouchingWater()) setVelocity(getVelocity().multiply(0.8).add(0, 0.03, 0));

        if (++life > LIFETIME || getY() < getWorld().getBottomY() - 64) discard();
    }

    @Override
    public boolean collidesWith(Entity other) { return false; }

    @Override
    public boolean canHit() { return !isRemoved(); }

    @Override
    public boolean isPushable() { return false; }

    @Override
    protected Entity.MoveEffect getMoveEffect() { return Entity.MoveEffect.NONE; }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (getWorld().isClient || isRemoved()) return false;
        if (source.getAttacker() instanceof PlayerEntity) {
            getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.4f, 0.6f);
            discard();
            return true;
        }
        return false;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.contains("PartData")) this.dataTracker.set(DATA, nbt.getCompound("PartData"));
        life = nbt.getInt("Life");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.put("PartData", data().copy());
        nbt.putInt("Life", life);
    }
}
