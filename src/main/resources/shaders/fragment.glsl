#version 330 core
in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUV;
out vec4 FragColor;

uniform sampler2D tex;
uniform vec3 color;
uniform vec3 viewPos;
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform vec3 ambientColor;
uniform vec3 fogColor;
uniform float fogDensity;

void main() {
    vec3 baseColor = texture(tex, vUV).rgb * color;

    vec3 N = normalize(vNormal);
    vec3 L = normalize(-lightDir);
    float diff = max(dot(N, L), 0.0);

    vec3 V = normalize(viewPos - vWorldPos);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 32.0) * 0.15;

    vec3 lit = baseColor * (ambientColor + lightColor * diff) + lightColor * spec;

    float dist = length(viewPos - vWorldPos);
    float fogFactor = clamp(exp(-fogDensity * dist), 0.0, 1.0);
    vec3 finalColor = mix(fogColor, lit, fogFactor);

    FragColor = vec4(finalColor, 1.0);
}
