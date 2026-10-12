package com.manbear.voice;

import com.manbear.ManbearConfig;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records short phrases from each player's microphone (RAM only) and replays them
 * from the Manbear's body, positionally, so the voice comes from where he is.
 */
public class VoiceMimicImpl implements VoiceBridge.Impl {
    private static final int RATE = 48000;
    private static final int MIN_SAMPLES = (int) (RATE * 0.45);
    private static final int MAX_SAMPLES = RATE * 6;

    private static class Recorder {
        final OpusDecoder decoder;
        final List<short[]> frames = new ArrayList<>();
        int samples;
        Recorder(OpusDecoder d) { decoder = d; }
    }

    private final VoicechatServerApi api;
    private final Map<UUID, Recorder> recorders = new ConcurrentHashMap<>();
    private final Map<UUID, List<short[]>> clips = new ConcurrentHashMap<>();
    private final Random rnd = new Random();

    public VoiceMimicImpl(VoicechatServerApi api) { this.api = api; }

    /** Called from the voice chat network thread for every microphone packet. */
    public void onPacket(UUID player, byte[] opus) {
        VoiceBridge.noteTalking(player);
        Recorder r = recorders.computeIfAbsent(player, k -> new Recorder(api.createDecoder()));
        synchronized (r) {
            if (opus == null || opus.length == 0) {
                finish(player, r);
                return;
            }
            short[] pcm = r.decoder.decode(opus);
            r.frames.add(pcm);
            r.samples += pcm.length;
            if (r.samples >= MAX_SAMPLES) finish(player, r);
        }
    }

    private void finish(UUID player, Recorder r) {
        if (r.samples >= MIN_SAMPLES) {
            short[] all = new short[r.samples];
            int pos = 0;
            for (short[] f : r.frames) { System.arraycopy(f, 0, all, pos, f.length); pos += f.length; }
            if (rms(all) > 350) {
                List<short[]> list = clips.computeIfAbsent(player, k -> new ArrayList<>());
                synchronized (list) {
                    list.add(all);
                    int max = ManbearConfig.VOICE_CLIPS_PER_PLAYER.get();
                    while (list.size() > max) list.remove(rnd.nextInt(list.size()));
                }
            }
        }
        r.frames.clear();
        r.samples = 0;
        r.decoder.resetState();
    }

    private static double rms(short[] s) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return Math.sqrt(sum / Math.max(1, s.length));
    }

    private short[] pick(UUID target) {
        // Prefer someone else's voice (to lure) when more than one player has been recorded.
        List<UUID> others = new ArrayList<>();
        for (Map.Entry<UUID, List<short[]>> e : clips.entrySet()) {
            if (!e.getKey().equals(target) && !e.getValue().isEmpty()) others.add(e.getKey());
        }
        UUID who = target;
        List<short[]> mine = clips.get(target);
        boolean haveMine = mine != null && !mine.isEmpty();
        if (!others.isEmpty() && (!haveMine || rnd.nextFloat() < 0.55F)) who = others.get(rnd.nextInt(others.size()));
        List<short[]> list = clips.get(who);
        if (list == null) return null;
        synchronized (list) {
            return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
        }
    }

    private static short[] resample(short[] in, double factor) {
        if (Math.abs(factor - 1.0) < 0.01) return in;
        int outLen = (int) (in.length / factor);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i * factor;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double f = src - i0;
            out[i] = (short) (in[Math.min(i0, in.length - 1)] * (1 - f) + in[i1] * f);
        }
        return out;
    }

    @Override
    public boolean mimic(Object entity, UUID targetPlayer) {
        try {
            short[] clip = pick(targetPlayer);
            if (clip == null) return false;
            short[] audio = resample(clip, ManbearConfig.VOICE_PITCH.get());
            EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(entity));
            if (channel == null) return false;
            channel.setDistance(ManbearConfig.VOICE_RANGE.get());
            OpusEncoder encoder = api.createEncoder();
            AudioPlayer player = api.createAudioPlayer(channel, encoder, audio);
            player.setOnStopped(encoder::close);
            player.startPlaying();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void forget(UUID player) {
        clips.remove(player);
        Recorder r = recorders.remove(player);
        if (r != null) r.decoder.close();
    }

    @Override
    public void clear() {
        clips.clear();
        for (Recorder r : recorders.values()) r.decoder.close();
        recorders.clear();
    }
}
