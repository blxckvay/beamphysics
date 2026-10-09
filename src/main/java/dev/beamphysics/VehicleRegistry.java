package dev.beamphysics;

import com.google.gson.Gson;
import dev.beamphysics.physics.VehicleConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All known vehicles. Built-in ones ship inside the jar under
 * {@code data/beamphysics/vehicles/<id>/}; extra ones are read from
 * {@code config/beamphysics/vehicles/<id>/} (vehicle.json + model.json), no code changes needed.
 */
public final class VehicleRegistry {
    public static final String DEFAULT = "test_suv";
    private static final Map<String, VehicleType> TYPES = new LinkedHashMap<>();

    private VehicleRegistry() {
    }

    public static void load() {
        TYPES.clear();
        try {
            String index = resource("/data/beamphysics/vehicles/index.json");
            String[] ids = new Gson().fromJson(index, String[].class);
            for (String id : ids) {
                String base = "/data/beamphysics/vehicles/" + id + "/";
                register(id, resource(base + "vehicle.json"), resource(base + "model.json"));
            }
        } catch (Exception e) {
            BeamPhysics.LOG.error("Failed to load built-in vehicles", e);
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("beamphysics").resolve("vehicles");
            Files.createDirectories(dir);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path sub : stream) {
                    Path vehicle = sub.resolve("vehicle.json"), model = sub.resolve("model.json");
                    if (!Files.isRegularFile(vehicle) || !Files.isRegularFile(model)) continue;
                    String id = sub.getFileName().toString();
                    try {
                        register(id, Files.readString(vehicle, StandardCharsets.UTF_8), Files.readString(model, StandardCharsets.UTF_8));
                        BeamPhysics.LOG.info("Loaded custom vehicle '{}' from config", id);
                    } catch (Exception e) {
                        BeamPhysics.LOG.error("Could not load custom vehicle '{}'", id, e);
                    }
                }
            }
        } catch (Exception e) {
            BeamPhysics.LOG.warn("Could not scan config/beamphysics/vehicles", e);
        }
        if (TYPES.isEmpty()) {
            VehicleConfig fallback = new VehicleConfig();
            fallback.validate();
            TYPES.put(DEFAULT, new VehicleType(DEFAULT, fallback, "{\"parts\":[]}"));
        }
        BeamPhysics.LOG.info("BeamPhysics vehicles: {}", TYPES.keySet());
    }

    private static void register(String id, String vehicleJson, String modelJson) {
        VehicleConfig cfg = VehicleConfig.fromJson(vehicleJson);
        cfg.id = id;
        VehicleType type = new VehicleType(id, cfg, modelJson);
        type.model(); // parse now so that a broken model fails loudly at startup
        TYPES.put(id, type);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = VehicleRegistry.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static VehicleType get(String id) {
        VehicleType t = TYPES.get(id);
        if (t == null) t = TYPES.get(DEFAULT);
        if (t == null) t = TYPES.values().iterator().next();
        return t;
    }

    public static boolean exists(String id) { return TYPES.containsKey(id); }

    public static Collection<String> ids() { return TYPES.keySet(); }
}
