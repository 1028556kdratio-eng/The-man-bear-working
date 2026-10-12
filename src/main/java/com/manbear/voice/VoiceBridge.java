package com.manbear.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Safe door between the mod and Simple Voice Chat. This class never touches the voice chat API,
 * so the mod loads fine when voice chat is not installed (mimicry is simply unavailable).
 */
public final class VoiceBridge {
    public interface Impl {
        /** Plays a recorded voice from the given entity. targetPlayer = who he is hunting. */
        boolean mimic(Object entity, UUID targetPlayer);
        void forget(UUID player);
        void clear();
    }

    private static volatile Impl impl;
    private static final Map<UUID, Long> LAST_TALK = new ConcurrentHashMap<>();

    private VoiceBridge() { }

    public static void setImpl(Impl i) { impl = i; }
    public static boolean isAvailable() { return impl != null; }

    public static void noteTalking(UUID player) { LAST_TALK.put(player, System.currentTimeMillis()); }

    public static boolean talkedRecently(UUID player) {
        Long t = LAST_TALK.get(player);
        return t != null && System.currentTimeMillis() - t < 1500L;
    }

    public static boolean mimic(Object entity, UUID targetPlayer) {
        Impl i = impl;
        return i != null && i.mimic(entity, targetPlayer);
    }

    public static void forget(UUID player) {
        Impl i = impl;
        if (i != null) i.forget(player);
        LAST_TALK.remove(player);
    }
}
