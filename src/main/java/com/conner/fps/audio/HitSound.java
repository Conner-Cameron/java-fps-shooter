package com.conner.fps.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import java.util.Random;

/**
 * A short, sharp metallic "tick" synthesized on the fly (no audio asset
 * needed) and played whenever a shot lands -- modeled after the classic
 * Call of Duty hit-marker sound: a crisp, percussive confirmation blip
 * rather than a musical "ding" sweep.
 */
public class HitSound {
    private static final int SAMPLE_RATE = 44100;
    private static final int DURATION_MS = 45;
    private static final double TONE_FREQ = 1900.0;
    private static final double TONE_DECAY = 90.0;   // higher = the tone dies out faster
    private static final double CLICK_DECAY = 4000.0; // very fast -- only colors the first ~1ms

    private final Clip clip;

    public HitSound() {
        Clip loaded;
        try {
            Random noise = new Random(7);
            int frameCount = SAMPLE_RATE * DURATION_MS / 1000;
            byte[] data = new byte[frameCount * 2];
            for (int i = 0; i < frameCount; i++) {
                double t = i / (double) SAMPLE_RATE;

                double toneEnvelope = Math.exp(-TONE_DECAY * t);
                double tone = Math.sin(2 * Math.PI * TONE_FREQ * t) * toneEnvelope;

                double clickEnvelope = Math.exp(-CLICK_DECAY * t);
                double click = (noise.nextDouble() * 2.0 - 1.0) * clickEnvelope;

                double sample = tone * 0.75 + click * 0.5;
                short clamped = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, sample * Short.MAX_VALUE * 0.8));
                data[i * 2] = (byte) (clamped & 0xFF);
                data[i * 2 + 1] = (byte) ((clamped >> 8) & 0xFF);
            }

            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            loaded = AudioSystem.getClip();
            loaded.open(format, data, 0, data.length);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // No audio device available -- hit still shows visually, just silent.
            loaded = null;
        }
        this.clip = loaded;
    }

    public void play() {
        if (clip == null) return;
        clip.stop();
        clip.setFramePosition(0);
        clip.start();
    }

    public void cleanup() {
        if (clip != null) clip.close();
    }
}
