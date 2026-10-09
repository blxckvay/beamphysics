package dev.beamphysics.physics;

import dev.beamphysics.math.V3;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the solid world around a vehicle. The Minecraft layer fills it once per game tick,
 * the physics substeps then query it without touching the world again.
 */
public final class Terrain {
    public final List<TerrainBox> boxes = new ArrayList<>();
    /** False when the surrounding chunks are not loaded: physics must freeze. */
    public boolean valid = true;

    public static final class RayHit {
        public double distance;
        public V3 normal;
        public TerrainBox box;
        public boolean startedInside;
    }

    public void clear() {
        boxes.clear();
        valid = true;
    }

    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double friction) {
        boxes.add(new TerrainBox(minX, minY, minZ, maxX, maxY, maxZ, friction));
    }

    public boolean contains(double x, double y, double z) {
        for (int i = 0, n = boxes.size(); i < n; i++) {
            if (boxes.get(i).contains(x, y, z)) return true;
        }
        return false;
    }

    /**
     * Ray cast against all boxes. Returns null on miss. If the origin lies inside a box the
     * result has {@code startedInside = true} and distance 0.
     */
    public RayHit raycast(V3 o, V3 d, double maxDist, RayHit out) {
        double best = maxDist;
        TerrainBox bestBox = null;
        int bestAxis = 0;
        double bestSign = 0;
        boolean inside = false;
        double idx = Math.abs(d.x) > 1e-12 ? 1.0 / d.x : Double.POSITIVE_INFINITY;
        double idy = Math.abs(d.y) > 1e-12 ? 1.0 / d.y : Double.POSITIVE_INFINITY;
        double idz = Math.abs(d.z) > 1e-12 ? 1.0 / d.z : Double.POSITIVE_INFINITY;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            TerrainBox b = boxes.get(i);
            double tmin = 0, tmax = best;
            int axis = -1;
            double sign = 0;
            // X slab
            if (idx == Double.POSITIVE_INFINITY) {
                if (o.x <= b.minX || o.x >= b.maxX) continue;
            } else {
                double t1 = (b.minX - o.x) * idx, t2 = (b.maxX - o.x) * idx;
                double s = -1;
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; s = 1; }
                if (t1 > tmin) { tmin = t1; axis = 0; sign = s; }
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) continue;
            }
            if (idy == Double.POSITIVE_INFINITY) {
                if (o.y <= b.minY || o.y >= b.maxY) continue;
            } else {
                double t1 = (b.minY - o.y) * idy, t2 = (b.maxY - o.y) * idy;
                double s = -1;
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; s = 1; }
                if (t1 > tmin) { tmin = t1; axis = 1; sign = s; }
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) continue;
            }
            if (idz == Double.POSITIVE_INFINITY) {
                if (o.z <= b.minZ || o.z >= b.maxZ) continue;
            } else {
                double t1 = (b.minZ - o.z) * idz, t2 = (b.maxZ - o.z) * idz;
                double s = -1;
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; s = 1; }
                if (t1 > tmin) { tmin = t1; axis = 2; sign = s; }
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) continue;
            }
            if (axis < 0) {
                // origin inside this box
                inside = true;
                best = 0;
                bestBox = b;
                break;
            }
            if (tmin < best) {
                best = tmin;
                bestBox = b;
                bestAxis = axis;
                bestSign = sign;
            }
        }
        if (bestBox == null) return null;
        out.distance = best;
        out.box = bestBox;
        out.startedInside = inside;
        if (inside) {
            out.normal = d.neg();
        } else {
            out.normal = bestAxis == 0 ? new V3(bestSign, 0, 0) : bestAxis == 1 ? new V3(0, bestSign, 0) : new V3(0, 0, bestSign);
        }
        return out;
    }
}
