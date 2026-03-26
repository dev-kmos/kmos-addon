package kmos.addon.modules;

import kmos.addon.KmosAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.sound.SoundCategory;

public class MasterMute extends Module {
    private double previousVolume = 1.0;
    private boolean hasPrevious = false;
    private boolean restoreOnDisable = true;
    private boolean settingVolume = false;

    public MasterMute() {
        super(KmosAddon.CATEGORY, "master-mute", "Sets master volume to 0 while enabled and restores the previous value on disable.");
    }

    @Override
    public void onActivate() {
        if (mc == null || mc.options == null) return;
        SimpleOption<Double> master = mc.options.getSoundVolumeOption(SoundCategory.MASTER);
        previousVolume = master.getValue();
        hasPrevious = true;
        restoreOnDisable = true;
        settingVolume = true;
        master.setValue(0.0);
        settingVolume = false;
    }

    @Override
    public void onDeactivate() {
        if (mc == null || mc.options == null) return;
        if (restoreOnDisable && hasPrevious) {
            mc.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(previousVolume);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        if (mc == null || mc.options == null) return;
        if (settingVolume) return;

        double current = mc.options.getSoundVolumeOption(SoundCategory.MASTER).getValue();
        if (current > 0.0001) {
            // Respect manual volume changes by disabling the module without restoring the old value.
            restoreOnDisable = false;
            toggle();
        }
    }
}


