package com.conner.fps.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

/**
 * A short descending "ding" synthesized on the fly (no audio asset needed)
 * and played whenever a shot lands, so a hit is heard as well as seen.
 */
public class HitSound {
    private static final int SAMPLE_RATE = 44100;
    private static final int DURATION_MS = 90;
    private static final double FREQ_START = 1200.0;
    private static final double FREQ_END = 650.0;

    private final Clip clip;

    public HitSound() {
        Clip loaded;
        try {
            int frameCount = SAMPLE_RATE * DURATION_MS / 1000;
            byte[] data = new byte[frameCount * 2];
            for (int i = 0; i < frameCount; i++) {
                double t = i / (double) SAMPLE_RATE;
                double sweep = i / (double) frameCount;
                double freq = FREQ_START + (FREQ_END - FREQ_START) * sweep;
                double envelope = 1.0 - sweep; // linear fade-out
                short sample = (short) (Math.sin(2 * Math.PI * freq * t) * envelope * Short.MAX_VALUE * 0.6);
                data[i * 2] = (byte) (sample & 0xFF);
                data[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
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
