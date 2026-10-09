package dev.beamphysics.math;

/**
 * Converts a camera basis (forward + up, world space) into Minecraft camera angles.
 * Minecraft builds its view rotation as Rz(roll) * Rx(pitch) * Ry(yaw + 180).
 */
public final class ViewAngles {
    public final float yaw, pitch, roll;

    public ViewAngles(float yaw, float pitch, float roll) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
    }

    public static ViewAngles fromBasis(V3 forward, V3 up) {
        V3 f = forward.normalizeOr(V3.Z);
        double pitch = -Math.asin(Math.max(-1, Math.min(1, f.y)));
        double yaw;
        if (Math.abs(f.x) < 1e-5 && Math.abs(f.z) < 1e-5) {
            yaw = Math.atan2(up.x, -up.z) * (f.y > 0 ? 1 : -1);
        } else {
            yaw = Math.atan2(-f.x, f.z);
        }
        // Reference (roll-free) basis for this yaw/pitch.
        V3 right0 = new V3(-Math.cos(yaw), 0, -Math.sin(yaw));
        V3 up0 = right0.cross(f).normalizeOr(V3.Y);
        double roll = Math.atan2(up.dot(right0), up.dot(up0));
        return new ViewAngles((float) Math.toDegrees(yaw), (float) Math.toDegrees(pitch), (float) Math.toDegrees(roll));
    }
}
