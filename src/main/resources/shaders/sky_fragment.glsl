#version 330 core
in vec2 vPos;
out vec4 FragColor;

uniform vec3 topColor;
uniform vec3 bottomColor;

void main() {
    float t = clamp(vPos.y * 0.5 + 0.5, 0.0, 1.0);
    FragColor = vec4(mix(bottomColor, topColor, t), 1.0);
}
