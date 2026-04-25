package kmos.addon.settings;

import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.NbtCompound;

import java.util.function.Consumer;

public class ActionButtonSetting extends Setting<Boolean> {
    private final String buttonText;
    private final Runnable action;

    public ActionButtonSetting(String name, String description, String buttonText, Consumer<Boolean> onChanged, Consumer<Setting<Boolean>> onModuleActivated, IVisible visible, Runnable action) {
        super(name, description, false, onChanged, onModuleActivated, visible);
        this.buttonText = buttonText;
        this.action = action;
    }

    public String getButtonText() {
        return buttonText;
    }

    public void press() {
        if (action != null) action.run();
    }

    @Override
    protected Boolean parseImpl(String str) {
        return false;
    }

    @Override
    protected boolean isValueValid(Boolean value) {
        return true;
    }

    @Override
    protected void resetImpl() {
        value = false;
    }

    @Override
    public NbtCompound save(NbtCompound tag) {
        return tag;
    }

    @Override
    public Boolean load(NbtCompound tag) {
        return false;
    }

    public static class Builder extends SettingBuilder<Builder, Boolean, ActionButtonSetting> {
        private String buttonText = "Run";
        private Runnable action;

        public Builder() {
            super(false);
        }

        public Builder buttonText(String buttonText) {
            this.buttonText = buttonText;
            return this;
        }

        public Builder action(Runnable action) {
            this.action = action;
            return this;
        }

        @Override
        public ActionButtonSetting build() {
            return new ActionButtonSetting(name, description, buttonText, onChanged, onModuleActivated, visible, action);
        }
    }
}
