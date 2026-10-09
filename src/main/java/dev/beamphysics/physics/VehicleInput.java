package dev.beamphysics.physics;

/** Raw driver input for one tick. */
public final class VehicleInput {
    /** W key, 0..1 */
    public double forward;
    /** S key, 0..1 */
    public double backward;
    /** -1 = right, +1 = left */
    public double steer;
    public boolean handbrake;

    public void clear() {
        forward = 0;
        backward = 0;
        steer = 0;
        handbrake = false;
    }
}
