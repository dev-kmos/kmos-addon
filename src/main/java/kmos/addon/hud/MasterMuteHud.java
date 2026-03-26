package kmos.addon.hud;

import kmos.addon.KmosAddon;
import kmos.addon.modules.MasterMute;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class MasterMuteHud extends HudElement {
    public static final HudElementInfo<MasterMuteHud> INFO = new HudElementInfo<>(
        KmosAddon.HUD_GROUP,
        "master-mute",
        "Master Mute",
        "Shows master mute status.",
        MasterMuteHud::new
    );

    private static final Color ACTIVE_COLOR = new Color(255, 80, 80, 255);
    private static final Color INACTIVE_COLOR = new Color(180, 180, 180, 255);

    public MasterMuteHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        boolean active = Modules.get().get(MasterMute.class).isActive();
        if (!active && !isInEditor()) {
            setSize(0, 0);
            return;
        }

        String text = "MUTE";
        setSize(renderer.textWidth(text), renderer.textHeight());
        renderer.text(text, x, y, active ? ACTIVE_COLOR : INACTIVE_COLOR, false);
    }
}


