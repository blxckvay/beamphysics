package dev.beamphysics;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BeamPhysics implements ModInitializer {
    public static final String MOD_ID = "beamphysics";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("BeamPhysics init");
    }
}
