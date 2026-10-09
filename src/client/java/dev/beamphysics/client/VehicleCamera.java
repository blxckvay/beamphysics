package dev.beamphysics.client;

import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import dev.beamphysics.math.ViewAngles;
import dev.beamphysics.physics.VehicleConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Vehicle cameras: chase (smoothly follows the direction of travel), orbit third person
 * (mouse controlled), first person from the driver's seat and a hood camera.
 * The first-person cameras take the full body orientation, including pitch and roll.
 */
public final class VehicleCamera {
    public enum Mode {
        CHASE("camera.beamphysics.chase", false),
        THIRD_PERSON("camera.beamphysics.third", false),
        INTERIOR("camera.beamphysics.interior", true),
        HOOD("camera.beamphysics.hood", true);

        public final String key;
        public final boolean firstPerson;

        Mode(String key, boolean firstPerson) {
            this.key = key;
            this.firstPerson = firstPerson;
        }
    }

    public static final class Result {
        public double x, y, z;
        public float yaw, pitch, roll;
    }

    public static Mode mode = Mode.CHASE;
    /** Roll for the current frame, applied to the view matrix by the GameRenderer mixin. */
    public static float currentRoll;

    private static final Result RESULT = new Result();
    private static double chaseYaw;
    private static boolean chaseValid;
    private static long lastFrame;
    private static Perspective savedPerspective;

    private VehicleCamera() {
    }

    public static void cycle() {
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
    }

    /** Called every client tick: keeps Minecraft's own perspective in step with our camera mode. */
    public static void tick(MinecraftClient client, boolean riding) {
        if (riding) {
            if (savedPerspective == null) savedPerspective = client.options.getPerspective();
            Perspective wanted = mode.firstPerson ? Perspective.FIRST_PERSON : Perspective.THIRD_PERSON_BACK;
            if (client.options.getPerspective() != wanted) client.options.setPerspective(wanted);
        } else if (savedPerspective != null) {
            client.options.setPerspective(savedPerspective);
            savedPerspective = null;
            chaseValid = false;
            currentRoll = 0;
        }
    }

    public static Result compute(Entity focused, float tickDelta) {
        currentRoll = 0;
        if (focused == null || !(focused.getVehicle() instanceof VehicleEntity vehicle)) {
            chaseValid = false;
            return null;
        }
        VehicleConfig cfg = vehicle.config();
        Quat rot = vehicle.renderRotation(tickDelta);
        V3 pos = vehicle.renderPosition(tickDelta);
        double carYaw = rot.mcYaw();
        float lookYaw = focused.getYaw(tickDelta);
        float lookPitch = MathHelper.clamp(focused.getPitch(tickDelta), -89f, 89f);
        Result r = RESULT;

        switch (mode) {
            case INTERIOR, HOOD -> {
                V3 eye = pos.add(rot.rotate(V3.of(mode == Mode.INTERIOR ? cfg.cameras.interior : cfg.cameras.hood)));
                // the player's look direction, expressed relative to the car body
                double yr = Math.toRadians(MathHelper.wrapDegrees(lookYaw - carYaw));
                double p = Math.toRadians(lookPitch);
                V3 fLocal = new V3(-Math.sin(yr) * Math.cos(p), -Math.sin(p), Math.cos(yr) * Math.cos(p));
                V3 rLocal = new V3(-Math.cos(yr), 0, -Math.sin(yr));
                V3 uLocal = rLocal.cross(fLocal);
                ViewAngles a = ViewAngles.fromBasis(rot.rotate(fLocal), rot.rotate(uLocal));
                r.x = eye.x;
                r.y = eye.y;
                r.z = eye.z;
                r.yaw = a.yaw;
                r.pitch = a.pitch;
                r.roll = a.roll;
                currentRoll = a.roll;
                chaseValid = false;
            }
            case THIRD_PERSON -> {
                V3 target = pos.add(0, 1.3, 0);
                double yr = Math.toRadians(lookYaw), p = Math.toRadians(lookPitch);
                V3 dir = new V3(-Math.sin(yr) * Math.cos(p), -Math.sin(p), Math.cos(yr) * Math.cos(p));
                V3 cam = clip(focused, target, target.addScaled(dir, -cfg.cameras.orbitDistance));
                r.x = cam.x;
                r.y = cam.y;
                r.z = cam.z;
                r.yaw = lookYaw;
                r.pitch = lookPitch;
                r.roll = 0;
                chaseValid = false;
            }
            default -> {
                long now = System.nanoTime();
                double dt = chaseValid ? Math.min(0.1, (now - lastFrame) / 1e9) : 0;
                lastFrame = now;
                // follow where the car is going rather than where it points, so slides are visible
                double targetYaw = carYaw;
                if (vehicle.snapshot != null) {
                    V3 v = vehicle.snapshot.velocity;
                    double hs = Math.sqrt(v.x * v.x + v.z * v.z);
                    if (hs > 4 && vehicle.snapshot.forwardSpeed > 0) {
                        double velYaw = Math.toDegrees(Math.atan2(-v.x, v.z));
                        double blend = Math.min(1, (hs - 4) / 6) * 0.6;
                        targetYaw = carYaw + MathHelper.wrapDegrees(velYaw - carYaw) * blend;
                    }
                }
                if (!chaseValid) {
                    chaseYaw = targetYaw;
                    chaseValid = true;
                } else {
                    double k = 1 - Math.exp(-dt * 5.0);
                    chaseYaw += MathHelper.wrapDegrees(targetYaw - chaseYaw) * k;
                }
                double yr = Math.toRadians(chaseYaw);
                V3 back = new V3(Math.sin(yr), 0, -Math.cos(yr));
                V3 target = pos.add(0, 1.4, 0);
                V3 cam = clip(focused, target, target.addScaled(back, cfg.cameras.chaseDistance).add(0, cfg.cameras.chaseHeight - 1.4, 0));
                V3 look = target.addScaled(back, -2.0).sub(cam);
                ViewAngles a = ViewAngles.fromBasis(look, V3.Y);
                r.x = cam.x;
                r.y = cam.y;
                r.z = cam.z;
                r.yaw = a.yaw;
                r.pitch = a.pitch;
                r.roll = 0;
            }
        }
        return r;
    }

    /** Pull the camera in front of any block between the car and the wanted camera position. */
    private static V3 clip(Entity viewer, V3 from, V3 to) {
        Vec3d a = new Vec3d(from.x, from.y, from.z), b = new Vec3d(to.x, to.y, to.z);
        HitResult hit = viewer.getWorld().raycast(new RaycastContext(a, b, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, viewer));
        if (hit.getType() == HitResult.Type.MISS) return to;
        double full = from.distance(to);
        double d = Math.max(0.6, hit.getPos().distanceTo(a) - 0.4);
        if (d >= full) return to;
        return from.lerp(to, d / full);
    }
}
