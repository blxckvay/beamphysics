package dev.beamphysics.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.beamphysics.BeamPhysics;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tyre grip per block, loaded from {@code config/beamphysics/surface_friction.json}.
 * Lookup order: exact block id, then the first keyword contained in the block id, then the default.
 */
public final class SurfaceFriction {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Table {
        public double defaultFriction = 0.9;
        public Map<String, Double> blocks = new LinkedHashMap<>();
        public Map<String, Double> keywords = new LinkedHashMap<>();
    }

    private static Table table = new Table();
    private static final Map<Block, Double> CACHE = new IdentityHashMap<>();

    private SurfaceFriction() {
    }

    public static void load() {
        CACHE.clear();
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("beamphysics");
            Path file = dir.resolve("surface_friction.json");
            if (!Files.exists(file)) {
                Files.createDirectories(dir);
                try (InputStream in = SurfaceFriction.class.getResourceAsStream("/data/beamphysics/surface_friction.json")) {
                    if (in != null) Files.write(file, in.readAllBytes());
                }
            }
            Table t = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Table.class);
            if (t != null) {
                if (t.blocks == null) t.blocks = new LinkedHashMap<>();
                if (t.keywords == null) t.keywords = new LinkedHashMap<>();
                table = t;
            }
        } catch (Exception e) {
            BeamPhysics.LOG.warn("Could not load surface_friction.json, using defaults", e);
            try (InputStream in = SurfaceFriction.class.getResourceAsStream("/data/beamphysics/surface_friction.json")) {
                if (in != null) table = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Table.class);
            } catch (Exception ignored) {
            }
        }
    }

    public static synchronized double of(Block block) {
        Double cached = CACHE.get(block);
        if (cached != null) return cached;
        double value = compute(block);
        CACHE.put(block, value);
        return value;
    }

    private static double compute(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        String full = id.toString();
        Double exact = table.blocks.get(full);
        if (exact != null) return exact;
        String path = id.getPath();
        for (Map.Entry<String, Double> e : table.keywords.entrySet()) {
            if (path.contains(e.getKey())) return e.getValue();
        }
        // unknown slippery blocks from other mods behave like ice
        if (block.getSlipperiness() > 0.7f) return 0.1;
        return table.defaultFriction;
    }
}
