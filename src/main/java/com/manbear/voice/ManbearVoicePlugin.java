package com.manbear.voice;

import com.manbear.ManbearConfig;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import net.minecraft.server.level.ServerPlayer;

/** Discovered and loaded by Simple Voice Chat itself, only if that mod is installed. */
@ForgeVoicechatPlugin
public class ManbearVoicePlugin implements VoicechatPlugin {
    private volatile VoiceMimicImpl impl;

    @Override
    public String getPluginId() { return "manbear"; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> {
            impl = new VoiceMimicImpl(e.getVoicechat());
            VoiceBridge.setImpl(impl);
        });
        registration.registerEvent(VoicechatServerStoppedEvent.class, e -> {
            VoiceBridge.setImpl(null);
            if (impl != null) impl.clear();
            impl = null;
        });
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoiceMimicImpl i = impl;
        if (i == null || !ManbearConfig.VOICE_MIMIC.get()) return;
        VoicechatConnection con = event.getSenderConnection();
        if (con == null) return;
        if (!(con.getPlayer().getPlayer() instanceof ServerPlayer sp)) return;
        i.onPacket(sp.getUUID(), event.getPacket().getOpusEncodedData());
    }
}
