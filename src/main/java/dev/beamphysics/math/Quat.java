package dev.beamphysics.math;

/**
 * Immutable unit quaternion (Hamilton convention, same component meaning as JOML).
 *
 * Vehicle body frame used across the mod: +X = left, +Y = up, +Z = forward.
 */
public final class Quat {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    public final double w, x, y, z;

    public Quat(double w, double x, double y, double z) {
        this.w = w;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Quat axisAngle(V3 axis, double angle) {
        V3 a = axis.normalize();
        double s = Math.sin(angle * 0.5);
        return new Quat(Math.cos(angle * 0.5), a.x * s, a.y * s, a.z * s);
    }

    /** Rotation about world +Y so that body forward points along Minecraft yaw (degrees). */
    public static Quat fromMcYaw(double yawDeg) {
        return axisAngle(V3.Y, Math.toRadians(-yawDeg));
    }

    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conj() { return new Quat(w, -x, -y, -z); }

    public Quat normalize() {
        double l = Math.sqrt(w * w + x * x + y * y + z * z);
        if (l < 1e-12 || !Double.isFinite(l)) return IDENTITY;
        return new Quat(w / l, x / l, y / l, z / l);
    }

    /** Rotate a vector from body space to world space. */
    public V3 rotate(V3 v) {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        double cx = y * v.z - z * v.y;
        double cy = z * v.x - x * v.z;
        double cz = x * v.y - y * v.x;
        double c2x = y * cz - z * cy;
        double c2y = z * cx - x * cz;
        double c2z = x * cy - y * cx;
        return new V3(v.x + 2 * (w * cx + c2x), v.y + 2 * (w * cy + c2y), v.z + 2 * (w * cz + c2z));
    }

    /** Rotate a vector from world space to body space. */
    public V3 unrotate(V3 v) { return conj().rotate(v); }

    /** Integrate world-space angular velocity over dt. */
    public Quat integrate(V3 omega, double dt) {
        double ang = omega.length() * dt;
        if (ang < 1e-12) return this;
        Quat dq = axisAngle(omega, ang);
        return dq.mul(this).normalize();
    }

    public Quat nlerp(Quat o, double t) {
        double d = w * o.w + x * o.x + y * o.y + z * o.z;
        double s = d < 0 ? -1 : 1;
        return new Quat(
                w + (o.w * s - w) * t,
                x + (o.x * s - x) * t,
                y + (o.y * s - y) * t,
                z + (o.z * s - z) * t).normalize();
    }

    public V3 forward() { return rotate(V3.Z); }
    public V3 up() { return rotate(V3.Y); }
    public V3 left() { return rotate(V3.X); }

    /** Minecraft yaw in degrees of the body forward axis (0 = +Z/south, 90 = -X/west). */
    public double mcYaw() {
        V3 f = forward();
        if (Math.abs(f.x) < 1e-6 && Math.abs(f.z) < 1e-6) {
            V3 u = up(); // pointing straight up/down: use the roof direction instead
            return Math.toDegrees(Math.atan2(u.x, -u.z)) * (f.y > 0 ? 1 : -1);
        }
        return Math.toDegrees(Math.atan2(-f.x, f.z));
    }

    public boolean isFinite() {
        return Double.isFinite(w) && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
