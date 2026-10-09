package dev.beamphysics.entity;

import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;

/** What a client needs to draw one vehicle at one instant. Sent by the server every tick. */
public final class VehicleSnapshot {
    public V3 position = V3.ZERO;
    public Quat rotation = Quat.IDENTITY;
    public V3 velocity = V3.ZERO;
    public V3 angularVelocity = V3.ZERO;
    public final float[] compression = new float[4];
    public final float[] spin = new float[4];
    public final float[] wheelSteer = new float[4];
    public final float[] slipRatio = new float[4];
    public final float[] slipAngle = new float[4];
    public final boolean[] contact = new boolean[4];
    public final float[][] contactPoint = new float[4][3];
    public float steer;
    public float rpm;
    public float throttle;
    public float forwardSpeed;
    public float temperature;
    public int gear;
    public boolean engineRunning;
    public boolean starting;
    public boolean automatic;
    public boolean handbrake;
    public boolean resting;

    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
