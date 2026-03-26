package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.util.InteractionGate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AutoVillagerClick extends Module {
    private static final int TRADE_RESERVE_TICKS = 12;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance used when searching for villagers.")
        .defaultValue(4.0)
        .min(0.0)
        .max(10.0)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay between villager interactions.")
        .defaultValue(5)
        .min(1)
        .max(200)
        .build()
    );

    private final Setting<Double> reenableRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("reenable-range")
        .description("A villager must leave this range before it becomes eligible again.")
        .defaultValue(5.0)
        .min(0.0)
        .max(10.0)
        .build()
    );

    private int cooldown = 0;
    private UUID lastVillager = null;
    private final Set<UUID> blocked = new HashSet<>();

    public AutoVillagerClick() {
        super(KmosAddon.CATEGORY, "auto-villager-click", "Interacts with nearby villagers one by one without repeating the same villager too soon.");
    }

    @Override
    public void onActivate() {
        cooldown = 0;
        lastVillager = null;
        blocked.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;

        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);
        if (!InteractionGate.canStart(InteractionGate.Owner.TradeFlow, now)) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        double r = range.get();
        double maxInteract = 4.5;

        double effectiveRange = Math.min(r, maxInteract);
        if (effectiveRange <= 0) return;

        Box box = mc.player.getBoundingBox().expand(effectiveRange);
        List<VillagerEntity> villagers = mc.world.getEntitiesByClass(
            VillagerEntity.class,
            box,
            v -> v.isAlive() && !v.isBaby()
        );

        updateBlocked();

        if (villagers.isEmpty()) {
            lastVillager = null;
            return;
        }

        VillagerEntity bestAny = null;
        VillagerEntity bestNotLast = null;
        double bestAnyDist = Double.MAX_VALUE;
        double bestNotLastDist = Double.MAX_VALUE;

        double maxDistSq = effectiveRange * effectiveRange;
        for (VillagerEntity v : villagers) {
            if (blocked.contains(v.getUuid())) continue;
            double dist = mc.player.squaredDistanceTo(v);
            if (dist > maxDistSq) continue;
            if (dist < bestAnyDist) {
                bestAnyDist = dist;
                bestAny = v;
            }
            if (lastVillager == null || !v.getUuid().equals(lastVillager)) {
                if (dist < bestNotLastDist) {
                    bestNotLastDist = dist;
                    bestNotLast = v;
                }
            }
        }

        VillagerEntity target = bestNotLast != null ? bestNotLast : bestAny;
        if (target == null) return;
        if (lastVillager != null && target.getUuid().equals(lastVillager)) return;

        aimAt(target);
        Vec3d hitPos = new Vec3d(
            target.getX(),
            target.getY() + target.getStandingEyeHeight() * 0.5,
            target.getZ()
        );
        EntityHitResult hit = new EntityHitResult(target, hitPos);
        boolean interacted = mc.interactionManager.interactEntityAtLocation(mc.player, target, hit, Hand.MAIN_HAND).isAccepted();
        if (!interacted) {
            mc.interactionManager.interactEntity(mc.player, target, Hand.MAIN_HAND);
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        InteractionGate.reserve(InteractionGate.Owner.TradeFlow, now, TRADE_RESERVE_TICKS);

        lastVillager = target.getUuid();
        blocked.add(lastVillager);
        cooldown = delayTicks.get();
    }

    private void aimAt(VillagerEntity target) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = new Vec3d(
            target.getX(),
            target.getY() + target.getStandingEyeHeight() * 0.5,
            target.getZ()
        );
        Vec3d diff = to.subtract(from);
        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private void updateBlocked() {
        double r = reenableRange.get();
        if (r <= 0) {
            blocked.clear();
            return;
        }

        Box box = mc.player.getBoundingBox().expand(r);
        List<VillagerEntity> nearby = mc.world.getEntitiesByClass(
            VillagerEntity.class,
            box,
            v -> v.isAlive() && !v.isBaby()
        );

        Set<UUID> stillNearby = new HashSet<>();
        for (VillagerEntity v : nearby) stillNearby.add(v.getUuid());

        blocked.removeIf(id -> !stillNearby.contains(id));
    }
}


