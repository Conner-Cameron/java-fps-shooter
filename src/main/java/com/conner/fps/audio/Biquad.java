package com.conner.fps.audio;

/** A second-order IIR filter (RBJ audio-EQ-cookbook lowpass / bandpass), enough to shape noise into thumps and clacks. */
final class Biquad {
    enum Type { LOWPASS, BANDPASS }

    private final Type type;
    private final double q;
    private final double rate;
    private double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    Biquad(Type type, double freq, double q, double rate) {
        this.type = type;
        this.q = q;
        this.rate = rate;
        setFrequency(freq);
    }

    void setFrequency(double freq) {
        double w0 = 2 * Math.PI * Math.min(freq, rate * 0.45) / rate;
        double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
        double a0 = 1 + alpha;
        if (type == Type.LOWPASS) {
            b0 = (1 - cos) / 2 / a0;
            b1 = (1 - cos) / a0;
            b2 = (1 - cos) / 2 / a0;
        } else { // constant 0 dB peak gain bandpass
            b0 = alpha / a0;
            b1 = 0;
            b2 = -alpha / a0;
        }
        a1 = -2 * cos / a0;
        a2 = (1 - alpha) / a0;
    }

    double process(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = x;
        y2 = y1;
        y1 = y;
        return y;
    }
}
