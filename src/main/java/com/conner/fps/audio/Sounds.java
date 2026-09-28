package com.conner.fps.audio;

import com.conner.fps.data.Weapons;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.util.Random;

/**
 * Every sound effect, synthesized at startup (no audio files) -- the same
 * recipes as the web client's js/audio.js: per-weapon gunshots (a noise
 * "crack" plus a low "thump"), a four-stage mechanical reload built from
 * filtered noise, a knife whoosh, and the crisp hit-marker tick. Each sound
 * owns a small pool of clips so rapid fire (the SMG) overlaps instead of
 * cutting itself off.
 */
public final class Sounds {
    private static final int RATE = 44100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int POOL = 4;

    private static final class Profile {
        final double duration, lowFreq, lowDecay, snapDecay, noiseMix, subMix;

        Profile(double duration, double lowFreq, double lowDecay, double snapDecay, double noiseMix, double subMix) {
            this.duration = duration;
            this.lowFreq = lowFreq;
            this.lowDecay = lowDecay;
            this.snapDecay = snapDecay;
            this.noiseMix = noiseMix;
            this.subMix = subMix;
        }
    }

    // Pistol, rifle, sniper, SMG -- same values as the web client.
    private static final Profile[] SHOTS = {
        new Profile(0.09, 90, 70, 220, 0.65, 0.5),
        new Profile(0.13, 70, 45, 150, 0.6, 0.65),
        new Profile(0.28, 55, 12, 90, 0.55, 0.85),
        new Profile(0.06, 110, 90, 300, 0.7, 0.4)
    };

    private static final class Pool {
        final Clip[] clips = new Clip[POOL];
        int next = 0;

        Pool(byte[] pcm) {
            try {
                for (int i = 0; i < POOL; i++) {
                    clips[i] = AudioSystem.getClip();
                    clips[i].open(FORMAT, pcm, 0, pcm.length);
                }
            } catch (Exception e) {
                java.util.Arrays.fill(clips, null); // no audio device: stay silent
            }
        }

        void play() {
            for (int k = 0; k < POOL; k++) {
                Clip c = clips[(next + k) % POOL];
                if (c != null && !c.isRunning()) {
                    next = (next + k + 1) % POOL;
                    c.setFramePosition(0);
                    c.start();
                    return;
                }
            }
            Clip c = clips[next];
            next = (next + 1) % POOL;
            if (c != null) {
                c.stop();
                c.setFramePosition(0);
                c.start();
            }
        }

        void close() {
            for (Clip c : clips) if (c != null) c.close();
        }
    }

    private final Pool[] shots = new Pool[SHOTS.length];
    private final Pool[] reloads = new Pool[Weapons.LOADOUT_COUNT];
    private final Pool swing;
    private final Pool tick;
    private final Random rnd = new Random(7);
    private boolean muted = false;

    public Sounds() {
        for (int i = 0; i < SHOTS.length; i++) shots[i] = new Pool(gunshotPcm(SHOTS[i]));
        for (int i = 0; i < reloads.length; i++) reloads[i] = new Pool(reloadPcm(Weapons.ALL[i].magSize));
        swing = new Pool(meleeSwingPcm());
        tick = new Pool(hitTickPcm());
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public void gunshot(int weaponIdx) {
        if (!muted && weaponIdx >= 0 && weaponIdx < shots.length) shots[weaponIdx].play();
    }

    public void reload(int weaponIdx) {
        if (!muted && weaponIdx >= 0 && weaponIdx < reloads.length) reloads[weaponIdx].play();
    }

    public void meleeSwing() {
        if (!muted) swing.play();
    }

    public void hitTick() {
        if (!muted) tick.play();
    }

    public void cleanup() {
        for (Pool p : shots) p.close();
        for (Pool p : reloads) p.close();
        swing.close();
        tick.close();
    }

    // ------------------------------------------------------------------ synthesis

    private byte[] gunshotPcm(Profile p) {
        int n = (int) (RATE * p.duration);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double noise = (rnd.nextDouble() * 2 - 1) * Math.exp(-p.snapDecay * t);
            double sub = Math.sin(2 * Math.PI * p.lowFreq * t) * Math.exp(-p.lowDecay * t);
            out[i] = noise * p.noiseMix + sub * p.subMix;
        }
        return pcm(out, 0.85);
    }

    /** Mag drops free, fresh mag slaps home, bolt released, bolt slams into battery; fewer rounds = slower, more deliberate. */
    private byte[] reloadPcm(int magSize) {
        double d = Math.max(0.3, Math.min(1.0, 1.0 - (magSize - 5) / 19.0 * 0.6));
        double boltRelease = 0.78 * d;
        double[] out = new double[(int) (RATE * (boltRelease + 0.035 + 0.25))];
        addClick(out, 0.04 * d, Biquad.Type.LOWPASS, 220, 0.7, 0.5, 0.09);              // mag drops free
        addClick(out, 0.55 * d, Biquad.Type.BANDPASS, 750, 1.4, 0.7, 0.05);             // fresh mag slaps home
        addClick(out, 0.55 * d, Biquad.Type.LOWPASS, 180, 0.7, 0.4, 0.08);
        addClick(out, boltRelease, Biquad.Type.BANDPASS, 2400, 7, 0.5, 0.03);           // bolt/slide released
        addClick(out, boltRelease + 0.035, Biquad.Type.BANDPASS, 1300, 5, 0.6, 0.045);  // bolt slams into battery
        addClick(out, boltRelease + 0.035, Biquad.Type.LOWPASS, 200, 0.7, 0.35, 0.06);
        return pcm(out, 0.9);
    }

    /** White noise through a filter, with an exponential decay envelope starting at {@code start} seconds. */
    private void addClick(double[] out, double start, Biquad.Type type, double freq, double q, double gain, double decay) {
        Biquad f = new Biquad(type, freq * (0.94 + rnd.nextDouble() * 0.12), q, RATE);
        int s = (int) (start * RATE);
        int len = (int) ((decay + 0.05) * RATE);
        for (int i = 0; i < len && s + i < out.length; i++) {
            double t = i / (double) RATE;
            double env = gain * Math.pow(0.001 / gain, Math.min(t / decay, 1.0)); // gain -> 0.001 over `decay`
            out[s + i] += f.process(rnd.nextDouble() * 2 - 1) * env;
        }
    }

    /** Quick bandpass noise sweep, high to low -- air being cut, not a gunshot. */
    private byte[] meleeSwingPcm() {
        int n = (int) (RATE * 0.18);
        double[] out = new double[n];
        Biquad f = new Biquad(Biquad.Type.BANDPASS, 2400, 1.1, RATE);
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            f.setFrequency(2400 * Math.pow(350.0 / 2400.0, Math.min(t / 0.12, 1.0)));
            double env = 0.5 * Math.pow(0.001 / 0.5, Math.min(t / 0.14, 1.0));
            out[i] = f.process(rnd.nextDouble() * 2 - 1) * env;
        }
        return pcm(out, 1.0);
    }

    /** The crisp CoD-style hit-marker tick: a short 1.9 kHz blip plus a click. */
    private byte[] hitTickPcm() {
        int n = (int) (RATE * 0.045);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double tone = Math.sin(2 * Math.PI * 1900 * t) * Math.exp(-90 * t);
            double click = (rnd.nextDouble() * 2 - 1) * Math.exp(-4000 * t);
            out[i] = tone * 0.75 + click * 0.5;
        }
        return pcm(out, 0.8);
    }

    private static byte[] pcm(double[] samples, double level) {
        byte[] data = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short v = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, samples[i] * level * Short.MAX_VALUE));
            data[i * 2] = (byte) (v & 0xFF);
            data[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return data;
    }
}
