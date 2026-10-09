package dev.beamphysics.math;

/** Immutable 3D vector (double precision). Pure Java, no Minecraft dependencies. */
public final class V3 {
    public static final V3 ZERO = new V3(0, 0, 0);
    public static final V3 X = new V3(1, 0, 0);
    public static final V3 Y = new V3(0, 1, 0);
    public static final V3 Z = new V3(0, 0, 1);

    public final double x, y, z;

    public V3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static V3 of(double[] a) {
        return new V3(a[0], a[1], a[2]);
    }

    public V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
    public V3 add(double ax, double ay, double az) { return new V3(x + ax, y + ay, z + az); }
    public V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
    public V3 scale(double s) { return new V3(x * s, y * s, z * s); }
    public V3 neg() { return new V3(-x, -y, -z); }
    /** this + o * s */
    public V3 addScaled(V3 o, double s) { return new V3(x + o.x * s, y + o.y * s, z + o.z * s); }
    public double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
    public V3 cross(V3 o) {
        return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
    public double lengthSq() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSq()); }
    public double distance(V3 o) { return sub(o).length(); }

    public V3 normalize() {
        double l = length();
        if (l < 1e-12) return ZERO;
        return new V3(x / l, y / l, z / l);
    }

    public V3 normalizeOr(V3 fallback) {
        double l = length();
        if (l < 1e-9) return fallback;
        return new V3(x / l, y / l, z / l);
    }

    public V3 lerp(V3 o, double t) {
        return new V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    public V3 mul(V3 o) { return new V3(x * o.x, y * o.y, z * o.z); }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
