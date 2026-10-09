package dev.beamphysics.client;

import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.entity.VehicleSnapshot;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.VehicleConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;

import java.util.Locale;

/** Speed / RPM / gear / temperature readout, plus the F8 physics debug panel. */
public final class VehicleHud {
    private VehicleHud() {
    }

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        if (!(client.player.getVehicle() instanceof VehicleEntity vehicle)) return;
        VehicleSnapshot s = vehicle.snapshot;
        if (s == null) return;
        VehicleConfig cfg = vehicle.config();
        DamageSystem damage = vehicle.physics().damage;
        TextRenderer font = client.textRenderer;

        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int pw = 132, ph = 62;
        int x = w - pw - 8, y = h - ph - 8;
        ctx.fill(x, y, x + pw, y + ph, 0x9010131A);
        ctx.fill(x, y, x + pw, y + 1, 0xFFD4541C);

        double kmh = Math.abs(s.velocity.length()) * 3.6;
        String speed = String.format(Locale.ROOT, "%.0f", kmh);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + 8, y + 7, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        ctx.drawTextWithShadow(font, speed, 0, 0, 0xFFFFFFFF);
        ctx.getMatrices().pop();
        ctx.drawTextWithShadow(font, "km/h", x + 10 + font.getWidth(speed) * 2, y + 15, 0xFFB0B6C0);

        String gear = s.gear < 0 ? "R" : s.gear == 0 ? "N" : Integer.toString(s.gear);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + pw - 26, y + 7, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        ctx.drawTextWithShadow(font, gear, 0, 0, s.gear < 0 ? 0xFFFF6A5A : 0xFFFFD27A);
        ctx.getMatrices().pop();
        ctx.drawTextWithShadow(font, s.automatic ? "A" : "M", x + pw - 12, y + 15, 0xFF8A93A0);

        // rev bar
        int bx = x + 8, by = y + 30, bw = pw - 16;
        double frac = Math.max(0, Math.min(1, s.rpm / (cfg.engine.redline * 1.05)));
        ctx.fill(bx, by, bx + bw, by + 5, 0xFF2A2E36);
        int red = (int) (bw * (cfg.engine.redline * 0.9 / (cfg.engine.redline * 1.05)));
        ctx.fill(bx + red, by, bx + bw, by + 5, 0xFF5A1E1E);
        ctx.fill(bx, by, bx + (int) (bw * frac), by + 5, frac > 0.86 ? 0xFFFF5A4A : 0xFF6FD06F);
        ctx.drawTextWithShadow(font, String.format(Locale.ROOT, "%.0f RPM", s.rpm), bx, by + 8, 0xFFE2E6EC);

        int tempColor = s.temperature > cfg.engine.overheatTemperature ? 0xFFFF5A4A
                : s.temperature > cfg.engine.normalTemperature + 12 ? 0xFFFFC14A : 0xFF9AD0FF;
        String temp = String.format(Locale.ROOT, "%.0f°C", s.temperature);
        ctx.drawTextWithShadow(font, temp, x + pw - 8 - font.getWidth(temp), by + 8, tempColor);

        String status = null;
        int statusColor = 0xFFFFC14A;
        if (!s.engineRunning) {
            if (damage.engineDead()) {
                status = I18n.translate("hud.beamphysics.engine_dead");
                statusColor = 0xFFFF5A4A;
            } else if (s.starting) {
                status = I18n.translate("hud.beamphysics.starting");
            } else {
                status = I18n.translate("hud.beamphysics.engine_off", ClientInput.KEY_ENGINE.getBoundKeyLocalizedText().getString());
            }
        } else if (s.temperature > cfg.engine.overheatTemperature) {
            status = I18n.translate("hud.beamphysics.overheat");
            statusColor = 0xFFFF5A4A;
        } else if (damage.coolant < 0.97) {
            status = I18n.translate("hud.beamphysics.coolant", Math.round(damage.coolant * 100));
        } else if (s.handbrake) {
            status = I18n.translate("hud.beamphysics.handbrake");
            statusColor = 0xFFB0B6C0;
        }
        if (status != null) ctx.drawTextWithShadow(font, status, x + 8, y + ph - 12, statusColor);

        if (ClientState.debug) renderDebug(ctx, font, s, damage);
    }

    private static void renderDebug(DrawContext ctx, TextRenderer font, VehicleSnapshot s, DamageSystem damage) {
        String[] names = {"FL", "FR", "RL", "RR"};
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("BeamPhysics debug (F8)");
        lines.add(String.format(Locale.ROOT, "speed %.1f m/s  (%.0f km/h)", s.velocity.length(), s.velocity.length() * 3.6));
        lines.add(String.format(Locale.ROOT, "velocity %.2f %.2f %.2f", s.velocity.x, s.velocity.y, s.velocity.z));
        lines.add(String.format(Locale.ROOT, "angular  %.2f %.2f %.2f", s.angularVelocity.x, s.angularVelocity.y, s.angularVelocity.z));
        lines.add(String.format(Locale.ROOT, "RPM %.0f  gear %s  throttle %.0f%%", s.rpm, s.gear < 0 ? "R" : s.gear == 0 ? "N" : "" + s.gear, s.throttle * 100));
        lines.add("");
        for (int i = 0; i < 4; i++) {
            lines.add(String.format(Locale.ROOT, "Wheel %s: %s  comp %.2f  slip %+.2f  angle %+.0f°%s",
                    names[i], s.contact[i] ? "contact" : "air    ", s.compression[i], s.slipRatio[i], Math.toDegrees(s.slipAngle[i]),
                    damage.wheelDetached[i] ? "  LOST" : ""));
        }
        lines.add("");
        StringBuilder zones = new StringBuilder("damage:");
        for (int i = 0; i < DamageSystem.ZONE_COUNT; i++) {
            if (damage.zones[i] > 0.01) zones.append(' ').append(DamageSystem.ZONE_NAMES[i]).append(' ').append(Math.round(damage.zones[i] * 100)).append('%');
        }
        lines.add(zones.toString());
        lines.add(String.format(Locale.ROOT, "engine %.0f%%  radiator %.0f%%  coolant %.0f%%", damage.engineHealth * 100, damage.radiatorHealth * 100, damage.coolant * 100));
        int y = 6;
        int width = 0;
        for (String l : lines) width = Math.max(width, font.getWidth(l));
        ctx.fill(4, 4, 12 + width, 8 + lines.size() * 10, 0x90101318);
        for (String l : lines) {
            ctx.drawTextWithShadow(font, l, 8, y, 0xFFE8ECF2);
            y += 10;
        }
    }
}
