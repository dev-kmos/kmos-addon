package kmos.addon.util;

public final class InteractionGate {
    public enum Owner {
        None,
        TradeFlow,
        ChestFlow,
        AnvilFlow
    }

    private static Owner activeOwner = Owner.None;
    private static Owner reservedOwner = Owner.None;
    private static long reservedUntil = Long.MIN_VALUE;

    private InteractionGate() {
    }

    public static boolean canStart(Owner owner, long now) {
        if (activeOwner != Owner.None && activeOwner != owner) return false;
        return reservedOwner == Owner.None || reservedOwner == owner || now > reservedUntil;
    }

    public static boolean acquire(Owner owner, long now) {
        if (!canStart(owner, now)) return false;
        activeOwner = owner;
        clearExpiredReservation(now);
        return true;
    }

    public static void release(Owner owner) {
        if (activeOwner == owner) activeOwner = Owner.None;
    }

    public static void reserve(Owner owner, long now, int ticks) {
        reservedOwner = owner;
        reservedUntil = now + Math.max(0, ticks);
    }

    public static void clearReservation(Owner owner) {
        if (reservedOwner == owner) {
            reservedOwner = Owner.None;
            reservedUntil = Long.MIN_VALUE;
        }
    }

    public static Owner getActiveOwner() {
        return activeOwner;
    }

    public static void clearExpiredReservation(long now) {
        if (reservedOwner != Owner.None && now > reservedUntil) {
            reservedOwner = Owner.None;
            reservedUntil = Long.MIN_VALUE;
        }
    }
}


