package dev.beamphysics.entity;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.VehicleRegistry;
import dev.beamphysics.VehicleType;
import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import dev.beamphysics.net.Net;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.Terrain;
import dev.beamphysics.physics.VehicleConfig;
import dev.beamphysics.physics.VehicleInput;
import dev.beamphysics.physics.VehiclePhysics;
import dev.beamphysics.physics.WheelPhysics;
import dev.beamphysics.world.TerrainSampler;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * The whole car is this one entity. Wheels, doors, bumpers and the hood are render parts driven by
 * the physics state; separate entities are only created when a part is actually torn off.
 *
 * Server: runs {@link VehiclePhysics} and the damage model, broadcasts state.
 * Client: keeps the last two snapshots and interpolates between them for rendering.
 */
public class VehicleEntity extends Entity {
    private static final TrackedData<String> TYPE_ID = DataTracker.registerData(VehicleEntity.class, TrackedDataHandlerRegistry.STRING);

    private VehicleType type;
    private VehiclePhysics physics;

    // ---- server side
    private final VehicleInput input = new VehicleInput();
    private final Terrain terrain = new Terrain();
    private int inputAge = 100;
    private boolean poseInitialized;
    private Quat loadedRotation;
    private Vec3d lastSyncedPos = Vec3d.ZERO;
    private int sentLatticeVersion = -1, sentStateVersion = -1;
    private int soundCooldown;
    private long restFingerprint;
    private float punchDamage;
    private int punchAge;

    // ---- client side
    public VehicleSnapshot prevSnapshot, snapshot, pendingSnapshot;
    /** Incremented on the client whenever a damage packet arrives. */
    public int clientDamageVersion;

    public VehicleEntity(EntityType<?> entityType, World world) {
        super(entityType, world);
        this.intersectionChecked = true;
    }

    public static VehicleEntity spawn(ServerWorld world, String typeId, double x, double y, double z, float yaw) {
        VehicleEntity v = new VehicleEntity(BeamPhysics.VEHICLE, world);
        v.setVehicleType(typeId);
        double rest = v.physics().restHeight();
        v.refreshPositionAndAngles(x, y + rest + 0.05, z, yaw, 0);
        v.physics().setPose(new V3(x, y + rest + 0.05, z), Quat.fromMcYaw(yaw));
        v.poseInitialized = true;
        v.lastSyncedPos = v.getPos();
        world.spawnEntity(v);
        return v;
    }

    // ------------------------------------------------------------------ type / physics access

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(TYPE_ID, VehicleRegistry.DEFAULT);
    }

    public void setVehicleType(String id) {
        this.dataTracker.set(TYPE_ID, id);
        this.physics = null;
        this.type = null;
    }

    public String getVehicleTypeId() { return this.dataTracker.get(TYPE_ID); }

    public VehicleType vehicleType() {
        String id = getVehicleTypeId();
        if (type == null || !type.id.equals(id)) {
            VehicleType resolved = VehicleRegistry.get(id);
            if (type != resolved) {
                type = resolved;
                physics = null;
            }
        }
        return type;
    }

    /** On the server this is the live simulation; on the client only its config and damage model are used. */
    public VehiclePhysics physics() {
        VehicleType t = vehicleType();
        if (physics == null) physics = new VehiclePhysics(t.config);
        return physics;
    }

    public VehicleConfig config() { return vehicleType().config; }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (TYPE_ID.equals(data)) {
            type = null;
            physics = null;
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            clientTick();
        } else {
            serverTick();
        }
    }

    private void serverTick() {
        VehiclePhysics p = physics();
        if (!poseInitialized) {
            Quat rot = loadedRotation != null ? loadedRotation : Quat.fromMcYaw(getYaw());
            p.setPose(new V3(getX(), getY(), getZ()), rot);
            poseInitialized = true;
            lastSyncedPos = getPos();
        } else if (getPos().squaredDistanceTo(lastSyncedPos) > 1e-6) {
            // something outside the physics moved us (/tp, portals, pistons)
            p.setPose(new V3(getX(), getY(), getZ()), p.rotation);
            p.wake();
        }

        // driver
        Entity first = getFirstPassenger();
        boolean hasDriver = first instanceof PlayerEntity;
        p.hasDriver = hasDriver;
        if (!hasDriver || ++inputAge > 10) input.clear();
        if (hasDriver && (input.forward > 0 || input.backward > 0 || input.steer != 0)) p.wake();

        // world snapshot
        V3 o = p.origin();
        double reach = 4.5 + p.speed() * 0.06;
        if (p.resting) {
            if (age % 10 == 0) {
                TerrainSampler.sample(getWorld(), o.x, o.y, o.z, 3.5, 2.5, 2.5, terrain);
                long fp = TerrainSampler.fingerprint(terrain);
                if (fp != restFingerprint) p.wake();
                restFingerprint = fp;
            }
        } else {
            TerrainSampler.sample(getWorld(), o.x, o.y + 0.5, o.z, reach, 4.5 + Math.max(0, -p.linearVelocity.y) * 0.06, 3.5, terrain);
            restFingerprint = TerrainSampler.fingerprint(terrain);
        }

        if (terrain.valid) {
            applyFluids(p);
            p.tick(0.05, input, terrain);
        }

        o = p.origin();
        setPosition(o.x, o.y, o.z);
        setYaw((float) p.rotation.mcYaw());
        setVelocity(p.linearVelocity.x / 20.0, p.linearVelocity.y / 20.0, p.linearVelocity.z / 20.0);
        lastSyncedPos = getPos();

        handlePhysicsEvents(p);
        if (!p.resting) {
            hitEntities(p);
            wheelEffects(p);
        }
        engineEffects(p);

        if (punchAge > 0 && --punchAge == 0) punchDamage = 0;
        if (soundCooldown > 0) soundCooldown--;

        // broadcast
        if (!p.resting || age % 10 == 0) {
            PacketByteBuf state = null;
            for (ServerPlayerEntity player : PlayerLookup.tracking(this)) {
                if (state == null) state = Net.writeState(this, p);
                ServerPlayNetworking.send(player, Net.STATE, new PacketByteBuf(state.copy()));
            }
        }
        DamageSystem d = p.damage;
        if (d.latticeVersion != sentLatticeVersion || (d.stateVersion != sentStateVersion && age % 10 == 0)) {
            sentLatticeVersion = d.latticeVersion;
            sentStateVersion = d.stateVersion;
            for (ServerPlayerEntity player : PlayerLookup.tracking(this)) {
                ServerPlayNetworking.send(player, Net.DAMAGE, Net.writeDamage(this, d));
            }
        }

        if (getY() < getWorld().getBottomY() - 80) discard();
    }

    private void applyFluids(VehiclePhysics p) {
        BlockPos pos = BlockPos.ofFloored(getX(), getY() + 0.5, getZ());
        if (getWorld().getFluidState(pos).isIn(FluidTags.WATER)) {
            p.linearVelocity = p.linearVelocity.scale(0.93).add(0, 0.32, 0);
            p.angularVelocity = p.angularVelocity.scale(0.9);
            p.wake();
        } else if (getWorld().getFluidState(pos).isIn(FluidTags.LAVA)) {
            p.linearVelocity = p.linearVelocity.scale(0.85).add(0, 0.2, 0);
            p.damage.engineHealth = Math.max(0, p.damage.engineHealth - 0.01);
            p.damage.stateVersion++;
            p.wake();
        }
    }

    private void handlePhysicsEvents(VehiclePhysics p) {
        ServerWorld world = (ServerWorld) getWorld();
        double strongest = 0;
        V3 where = null;
        for (VehiclePhysics.Impact impact : p.impacts) {
            if (impact.speed > strongest) {
                strongest = impact.speed;
                where = impact.position;
            }
        }
        p.impacts.clear();
        if (where != null && soundCooldown == 0) {
            float volume = (float) Math.min(2.0, 0.3 + strongest / 14.0);
            float pitch = (float) Math.max(0.5, 1.25 - strongest / 40.0);
            world.playSound(null, where.x, where.y, where.z, SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.NEUTRAL, volume, pitch);
            if (strongest > 12) {
                world.playSound(null, where.x, where.y, where.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.NEUTRAL, volume * 0.6f, 0.6f);
            }
            int count = (int) Math.min(40, strongest * 2);
            world.spawnParticles(ParticleTypes.CRIT, where.x, where.y, where.z, count, 0.3, 0.3, 0.3, 0.4);
            if (strongest > 8) world.spawnParticles(ParticleTypes.LARGE_SMOKE, where.x, where.y, where.z, count / 4, 0.4, 0.3, 0.4, 0.02);
            soundCooldown = 4;
        }

        for (VehiclePhysics.Detachment det : p.detachments) {
            VehicleConfig.Part part = config().damage.parts[det.part];
            DetachedPartEntity.spawn(world, getVehicleTypeId(), part.name, part.wheel, det.position, det.velocity,
                    p.rotation, p.damage.latticeBytes());
            world.playSound(null, det.position.x, det.position.y, det.position.z, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.NEUTRAL, 1.0f, 0.6f);
        }
        if (!p.detachments.isEmpty()) sentStateVersion = -1;
        p.detachments.clear();
    }

    private void hitEntities(VehiclePhysics p) {
        double speed = p.speed();
        if (speed < 3.5) return;
        List<Entity> list = getWorld().getOtherEntities(this, getBoundingBox().expand(0.35, 0, 0.35),
                e -> e instanceof LivingEntity && e.isAlive() && !hasPassenger(e) && e.getVehicle() == null);
        for (Entity e : list) {
            V3 toEntity = new V3(e.getX() - getX(), 0, e.getZ() - getZ());
            if (toEntity.dot(p.linearVelocity) <= 0) continue; // only what we are driving into
            e.damage(getDamageSources().generic(), (float) Math.min(40, (speed - 2.5) * 1.3));
            e.addVelocity(p.linearVelocity.x / 20.0 * 1.1, 0.25 + speed * 0.02, p.linearVelocity.z / 20.0 * 1.1);
            e.velocityModified = true;
            // the car feels it too
            p.linearVelocity = p.linearVelocity.scale(0.97);
        }
    }

    private void wheelEffects(VehiclePhysics p) {
        ServerWorld world = (ServerWorld) getWorld();
        for (WheelPhysics w : p.wheels) {
            if (!w.contact || w.detached) continue;
            double slide = Math.abs(w.slipRatio) > 0.45 ? Math.abs(w.wheelAngularVelocity * w.radius - 0) * 0.1 : 0;
            double side = Math.abs(w.slipAngle) > 0.3 ? p.speed() * 0.15 : 0;
            double intensity = Math.max(slide, side);
            if (intensity < 0.6 || p.speed() + Math.abs(w.wheelAngularVelocity) * w.radius < 4) continue;
            if (random.nextFloat() < Math.min(1.0, intensity * 0.5)) {
                world.spawnParticles(w.surfaceFriction < 0.7 ? ParticleTypes.POOF : ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        w.contactPoint.x, w.contactPoint.y + 0.1, w.contactPoint.z, 1, 0.15, 0.05, 0.15, 0.01);
            }
        }
    }

    private void engineEffects(VehiclePhysics p) {
        if (age % 4 != 0) return;
        DamageSystem d = p.damage;
        ServerWorld world = (ServerWorld) getWorld();
        V3 hood = p.toWorld(new V3(0, 1.1, config().damage.radiatorZ - 0.4));
        if (p.engine.running && d.coolant < 0.98 && d.temperature > 100) {
            world.spawnParticles(ParticleTypes.CLOUD, hood.x, hood.y, hood.z, 2, 0.3, 0.1, 0.3, 0.02);
        }
        if (d.engineHealth < 0.4 && (p.engine.running || d.temperature > 110)) {
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, hood.x, hood.y, hood.z, 1, 0.25, 0.1, 0.25, 0.01);
        }
    }

    private void clientTick() {
        if (pendingSnapshot != null) {
            prevSnapshot = snapshot != null ? snapshot : pendingSnapshot;
            snapshot = pendingSnapshot;
            pendingSnapshot = null;
        } else if (snapshot != null) {
            prevSnapshot = snapshot;
        }
        if (snapshot != null) {
            setPosition(snapshot.position.x, snapshot.position.y, snapshot.position.z);
            float yaw = (float) snapshot.rotation.mcYaw();
            setYaw(yaw);
            for (Entity passenger : getPassengerList()) {
                if (passenger instanceof LivingEntity living) living.setBodyYaw(yaw);
            }
        }
    }

    /** Called from the client packet handler. */
    public void acceptSnapshot(VehicleSnapshot s) {
        pendingSnapshot = s;
        if (snapshot == null) {
            snapshot = s;
            prevSnapshot = s;
            setPosition(s.position.x, s.position.y, s.position.z);
            resetPosition();
        }
    }

    public Quat renderRotation(float tickDelta) {
        if (snapshot == null) return Quat.fromMcYaw(getYaw());
        if (prevSnapshot == null) return snapshot.rotation;
        return prevSnapshot.rotation.nlerp(snapshot.rotation, tickDelta);
    }

    public V3 renderPosition(float tickDelta) {
        if (snapshot == null || prevSnapshot == null) return new V3(getX(), getY(), getZ());
        return prevSnapshot.position.lerp(snapshot.position, tickDelta);
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps, boolean interpolate) {
        // position comes from our own state packets; ignore vanilla interpolation
        if (snapshot == null) {
            setPosition(x, y, z);
            setYaw(yaw);
        }
    }

    // ------------------------------------------------------------------ driver

    public boolean isDriver(Entity e) { return getFirstPassenger() == e; }

    public void setDriverInput(float forward, float backward, float steer, boolean handbrake) {
        input.forward = forward;
        input.backward = backward;
        input.steer = steer;
        input.handbrake = handbrake;
        inputAge = 0;
    }

    public void driverAction(int action, ServerPlayerEntity player) {
        VehiclePhysics p = physics();
        p.wake();
        switch (action) {
            case Net.ACTION_ENGINE -> {
                if (!p.engine.running && p.damage.engineDead()) {
                    player.sendMessage(Text.translatable("message.beamphysics.engine_dead"), true);
                    getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.NEUTRAL, 0.8f, 0.7f);
                } else {
                    p.engine.toggle(p.damage);
                    getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.NEUTRAL, 0.6f, 0.8f);
                }
            }
            case Net.ACTION_SHIFT_UP -> {
                p.transmission.automatic = false;
                p.transmission.shiftUp();
            }
            case Net.ACTION_SHIFT_DOWN -> {
                p.transmission.automatic = false;
                p.transmission.shiftDown();
            }
            case Net.ACTION_TOGGLE_AUTO -> {
                p.transmission.automatic = !p.transmission.automatic;
                if (p.transmission.automatic && p.transmission.gear == 0) p.transmission.shiftTo(1);
                player.sendMessage(Text.translatable(p.transmission.automatic ? "message.beamphysics.auto" : "message.beamphysics.manual"), true);
            }
            default -> {
            }
        }
    }

    public boolean tryEnter(PlayerEntity player) {
        if (getPassengerList().size() >= config().seats.length) return false;
        boolean wasEmpty = getPassengerList().isEmpty();
        if (!player.startRiding(this)) return false;
        physics().wake();
        if (wasEmpty && !physics().engine.running) {
            player.sendMessage(Text.translatable("message.beamphysics.start_hint"), true);
        }
        return true;
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.shouldCancelInteraction()) return ActionResult.PASS;
        if (getWorld().isClient) return ActionResult.SUCCESS;
        return tryEnter(player) ? ActionResult.CONSUME : ActionResult.PASS;
    }

    // ------------------------------------------------------------------ passengers

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengerList().size() < config().seats.length;
    }

    /** No vanilla "controlling passenger": the server simulates, the client only sends input. */
    @Override
    public LivingEntity getControllingPassenger() { return null; }

    private Quat currentRotation() {
        if (getWorld().isClient) return snapshot != null ? snapshot.rotation : Quat.fromMcYaw(getYaw());
        return physics().rotation;
    }

    public V3 seatLocal(int index) {
        VehicleConfig.Seat[] seats = config().seats;
        VehicleConfig.Seat s = seats[Math.max(0, Math.min(seats.length - 1, index))];
        return new V3(s.x, s.y, s.z);
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, Entity.PositionUpdater positionUpdater) {
        if (!hasPassenger(passenger)) return;
        int index = Math.max(0, getPassengerList().indexOf(passenger));
        V3 seat = currentRotation().rotate(seatLocal(index));
        positionUpdater.accept(passenger, getX() + seat.x, getY() + seat.y, getZ() + seat.z);
    }

    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        Quat rot = currentRotation();
        int index = Math.max(0, getPassengerList().indexOf(passenger));
        V3 seat = seatLocal(index);
        if (rot.up().y > 0.5) {
            double side = seat.x >= 0 ? 1 : -1;
            for (double dist : new double[]{1.9, 2.6}) {
                V3 out = rot.rotate(new V3(side * dist, 0.2, seat.z));
                Vec3d candidate = new Vec3d(getX() + out.x, getY() + out.y, getZ() + out.z);
                Box box = passenger.getDimensions(passenger.getPose()).getBoxAt(candidate);
                if (getWorld().isSpaceEmpty(passenger, box)) return candidate;
            }
        }
        return new Vec3d(getX(), getY() + 2.0, getZ());
    }

    // ------------------------------------------------------------------ interaction with the world

    @Override
    public boolean canHit() { return !isRemoved(); }

    @Override
    public boolean isCollidable() { return true; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public void pushAwayFrom(Entity entity) {
    }

    @Override
    protected Entity.MoveEffect getMoveEffect() { return Entity.MoveEffect.NONE; }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) { return false; }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (getWorld().isClient || isRemoved()) return false;
        if (!(source.getAttacker() instanceof PlayerEntity player)) return false;
        if (hasPassenger(player)) return false;
        if (player.getAbilities().creativeMode) {
            discard();
            return true;
        }
        punchDamage += Math.max(1, amount);
        punchAge = 60;
        getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.NEUTRAL, 0.5f, 1.4f);
        if (punchDamage >= 12) {
            dropStack(getPickBlockStack());
            discard();
        }
        return true;
    }

    @Override
    public ItemStack getPickBlockStack() { return new ItemStack(BeamPhysics.TEST_SUV_ITEM); }

    @Override
    public Box getVisibilityBoundingBox() { return getBoundingBox().expand(3.5); }

    @Override
    public boolean shouldRender(double distance) { return distance < 200 * 200; }

    @Override
    public void onStartedTrackingBy(ServerPlayerEntity player) {
        super.onStartedTrackingBy(player);
        VehiclePhysics p = physics();
        ServerPlayNetworking.send(player, Net.STATE, Net.writeState(this, p));
        ServerPlayNetworking.send(player, Net.DAMAGE, Net.writeDamage(this, p.damage));
    }

    // ------------------------------------------------------------------ commands

    public void repair() {
        VehiclePhysics p = physics();
        p.damage.repair();
        p.wake();
        sentStateVersion = -1;
    }

    public void flipUpright() {
        VehiclePhysics p = physics();
        p.setPose(new V3(getX(), getY() + 1.2, getZ()), Quat.fromMcYaw(p.rotation.mcYaw()));
        p.stop();
        p.wake();
        setPosition(getX(), getY() + 1.2, getZ());
        lastSyncedPos = getPos();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.contains("VehicleType")) setVehicleType(nbt.getString("VehicleType"));
        VehiclePhysics p = physics();
        if (nbt.contains("Rot")) {
            long[] r = nbt.getLongArray("Rot");
            if (r.length == 4) {
                loadedRotation = new Quat(Double.longBitsToDouble(r[0]), Double.longBitsToDouble(r[1]),
                        Double.longBitsToDouble(r[2]), Double.longBitsToDouble(r[3])).normalize();
            }
        }
        DamageSystem d = p.damage;
        if (nbt.contains("Lattice")) d.setLatticeBytes(nbt.getByteArray("Lattice"));
        if (nbt.contains("PartHealth")) {
            long[] h = nbt.getLongArray("PartHealth");
            for (int i = 0; i < h.length && i < d.partCount(); i++) d.partHealth[i] = Double.longBitsToDouble(h[i]);
        }
        if (nbt.contains("Zones")) {
            long[] z = nbt.getLongArray("Zones");
            for (int i = 0; i < z.length && i < d.zones.length; i++) d.zones[i] = Double.longBitsToDouble(z[i]);
        }
        if (nbt.contains("Wheels")) {
            long[] w = nbt.getLongArray("Wheels");
            for (int i = 0; i < 4 && i * 3 + 2 < w.length; i++) {
                d.wheelCamber[i] = Double.longBitsToDouble(w[i * 3]);
                d.wheelToe[i] = Double.longBitsToDouble(w[i * 3 + 1]);
                d.wheelJammed[i] = w[i * 3 + 2] != 0;
            }
        }
        d.setDetachedMask(nbt.getInt("Detached"));
        if (nbt.contains("EngineHealth")) d.engineHealth = nbt.getDouble("EngineHealth");
        if (nbt.contains("Radiator")) d.radiatorHealth = nbt.getDouble("Radiator");
        if (nbt.contains("Coolant")) d.coolant = nbt.getDouble("Coolant");
        if (nbt.contains("Temperature")) d.temperature = nbt.getDouble("Temperature");
        p.engine.running = nbt.getBoolean("EngineRunning");
        if (p.engine.running) p.engine.omega = 90;
        if (nbt.contains("Automatic")) p.transmission.automatic = nbt.getBoolean("Automatic");
        poseInitialized = false;
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("VehicleType", getVehicleTypeId());
        VehiclePhysics p = physics();
        Quat q = p.rotation;
        nbt.putLongArray("Rot", new long[]{Double.doubleToLongBits(q.w), Double.doubleToLongBits(q.x),
                Double.doubleToLongBits(q.y), Double.doubleToLongBits(q.z)});
        DamageSystem d = p.damage;
        nbt.putByteArray("Lattice", d.latticeBytes());
        long[] h = new long[d.partCount()];
        for (int i = 0; i < h.length; i++) h[i] = Double.doubleToLongBits(d.partHealth[i]);
        nbt.putLongArray("PartHealth", h);
        long[] z = new long[d.zones.length];
        for (int i = 0; i < z.length; i++) z[i] = Double.doubleToLongBits(d.zones[i]);
        nbt.putLongArray("Zones", z);
        long[] w = new long[12];
        for (int i = 0; i < 4; i++) {
            w[i * 3] = Double.doubleToLongBits(d.wheelCamber[i]);
            w[i * 3 + 1] = Double.doubleToLongBits(d.wheelToe[i]);
            w[i * 3 + 2] = d.wheelJammed[i] ? 1 : 0;
        }
        nbt.putLongArray("Wheels", w);
        nbt.putInt("Detached", d.detachedMask());
        nbt.putDouble("EngineHealth", d.engineHealth);
        nbt.putDouble("Radiator", d.radiatorHealth);
        nbt.putDouble("Coolant", d.coolant);
        nbt.putDouble("Temperature", d.temperature);
        nbt.putBoolean("EngineRunning", p.engine.running);
        nbt.putBoolean("Automatic", p.transmission.automatic);
    }
}
