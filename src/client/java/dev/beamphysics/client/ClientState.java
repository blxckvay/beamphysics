package dev.beamphysics.client;

/** Small bits of client-only state shared between input, camera, HUD and renderer. */
public final class ClientState {
    public static boolean debug;
    /** True when the local player is seated in a vehicle. */
    public static boolean riding;
    /** True when the local player stands close enough to a vehicle for F to mean "get in". */
    public static boolean nearVehicle;

    private ClientState() {
    }
}
