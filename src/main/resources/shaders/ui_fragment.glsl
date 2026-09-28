#version 330 core
in vec2 vUV;
in vec4 vColor;
in vec2 vPix;
out vec4 FragColor;

uniform sampler2D tex;
uniform int mode;        // 0 solid, 1 font (alpha from texture), 2 scope mask, 3 texture * color, 4 soft filled circle
uniform vec2 center;     // scope mask center (pixels)
uniform float radius;    // scope mask radius (pixels)

void main() {
    if (mode == 0) {
        FragColor = vColor;
    } else if (mode == 1) {
        FragColor = vec4(vColor.rgb, vColor.a * texture(tex, vUV).a);
    } else if (mode == 3) {
        FragColor = texture(tex, vUV) * vColor;
    } else if (mode == 4) {
        float d = length(vUV * 2.0 - 1.0);
        FragColor = vec4(vColor.rgb, vColor.a * smoothstep(1.0, 0.8, d));
    } else {
        // Sniper scope: opaque black outside the lens, a soft dark vignette just inside its rim.
        float d = length(vPix - center);
        float outside = smoothstep(radius - 1.5, radius + 1.5, d);
        float rim = smoothstep(radius * 0.78, radius, d) * 0.65;
        FragColor = vec4(0.0, 0.0, 0.0, max(outside, rim));
    }
}
