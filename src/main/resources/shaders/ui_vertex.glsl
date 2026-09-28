#version 330 core
layout (location = 0) in vec2 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in vec4 aColor;

uniform vec2 screen;

out vec2 vUV;
out vec4 vColor;
out vec2 vPix;

void main() {
    vec2 ndc = vec2(aPos.x / screen.x * 2.0 - 1.0, 1.0 - aPos.y / screen.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
    vPix = aPos;
}
