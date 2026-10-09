package dev.beamphysics;

import dev.beamphysics.model.VehicleModel;
import dev.beamphysics.physics.VehicleConfig;

/** A loaded vehicle definition: physics config plus the visual model source. */
public final class VehicleType {
    public final String id;
    public final VehicleConfig config;
    private final String modelJson;
    private VehicleModel model;

    public VehicleType(String id, VehicleConfig config, String modelJson) {
        this.id = id;
        this.config = config;
        this.modelJson = modelJson;
    }

    public synchronized VehicleModel model() {
        if (model == null) model = VehicleModel.fromJson(modelJson, config.wheels.radius, config.wheels.width);
        return model;
    }
}
