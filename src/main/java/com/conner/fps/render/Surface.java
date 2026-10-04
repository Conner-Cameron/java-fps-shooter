package com.conner.fps.render;

/**
 * One surface's maps, bound together for the surface shader (see surface_fragment.glsl): color on
 * unit 0, normal on 1, ARM (occlusion + roughness) on 2, and the leaf cutout opacity on 3.
 */
public final class Surface {
    public final Texture color;
    public final Texture normal;
    public final Texture arm;
    public final Texture opacity;

    public Surface(Texture color, Texture normal, Texture arm, Texture opacity) {
        this.color = color;
        this.normal = normal;
        this.arm = arm;
        this.opacity = opacity;
    }

    public void bind() {
        color.bind(0);
        normal.bind(1);
        arm.bind(2);
        opacity.bind(3);
    }
}
