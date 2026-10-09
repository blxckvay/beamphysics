package dev.beamphysics.client;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.entity.VehicleSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Looping engine and tyre-squeal sounds whose pitch and volume follow the physics state. */
public final class VehicleSounds {
    private static final Map<Integer, Loop[]> ACTIVE = new HashMap<>();

    private VehicleSounds() {
    }

    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            ACTIVE.clear();
            return;
        }
        for (Entity e : client.world.getEntities()) {
            if (e instanceof VehicleEntity v && !ACTIVE.containsKey(v.getId())) {
                Loop engine = new Loop(v, BeamPhysics.ENGINE_SOUND, false);
                Loop skid = new Loop(v, BeamPhysics.SKID_SOUND, true);
                ACTIVE.put(v.getId(), new Loop[]{engine, skid});
                client.getSoundManager().play(engine);
                client.getSoundManager().play(skid);
            }
        }
        Iterator<Map.Entry<Integer, Loop[]>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Loop[] loops = it.next().getValue();
            if (loops[0].finished()) it.remove();
        }
    }

    private static final class Loop extends MovingSoundInstance {
        private final VehicleEntity vehicle;
        private final boolean skid;
        private boolean finished;

        Loop(VehicleEntity vehicle, SoundEvent event, boolean skid) {
            super(event, SoundCategory.NEUTRAL, SoundInstance.createRandom());
            this.vehicle = vehicle;
            this.skid = skid;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.0f;
            this.pitch = 1.0f;
            this.x = vehicle.getX();
            this.y = vehicle.getY();
            this.z = vehicle.getZ();
        }

        boolean finished() { return finished; }

        @Override
        public boolean shouldAlwaysPlay() { return true; }

        @Override
        public boolean canPlay() { return !vehicle.isRemoved(); }

        @Override
        public void tick() {
            if (vehicle.isRemoved()) {
                finished = true;
                setDone();
                return;
            }
            x = vehicle.getX();
            y = vehicle.getY() + 0.8;
            z = vehicle.getZ();
            VehicleSnapshot s = vehicle.snapshot;
            float targetVolume = 0, targetPitch = pitch;
            if (s != null) {
                if (skid) {
                    float worst = 0;
                    for (int i = 0; i < 4; i++) {
                        if (!s.contact[i]) continue;
                        float slip = Math.max(Math.abs(s.slipRatio[i]) - 0.25f, Math.abs(s.slipAngle[i]) - 0.2f);
                        worst = Math.max(worst, slip);
                    }
                    float speed = (float) s.velocity.length();
                    targetVolume = speed > 2.5f ? Math.min(0.8f, worst * 1.6f) : 0;
                    targetPitch = 0.8f + Math.min(0.5f, speed / 60f);
                } else if (s.engineRunning || s.starting) {
                    float redline = (float) vehicle.config().engine.redline;
                    targetVolume = s.starting ? 0.35f : 0.32f + 0.5f * s.throttle;
                    targetPitch = Math.max(0.5f, Math.min(2.0f, s.starting ? 0.5f : s.rpm / (redline * 0.5f)));
                }
            }
            volume += (targetVolume - volume) * 0.4f;
            pitch += (targetPitch - pitch) * 0.5f;
        }
    }
}
