#version 330 core
// World surfaces: the web client's photo PBR look, approximated for the custom pipeline.
// Color, normal (OpenGL convention) and ARM (R = ambient occlusion, G = roughness) maps; a
// cutout opacity map for the leaf cards. Lighting mirrors web/js/core.js: an ambient term, a sky/ground
// hemisphere term and one directional light.
in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUV;
out vec4 FragColor;

uniform sampler2D tex;        // color (unit 0)
uniform sampler2D normalTex;  // tangent-space normal, OpenGL convention (unit 1)
uniform sampler2D armTex;     // R = ambient occlusion, G = roughness (unit 2)
uniform sampler2D opacityTex; // leaf cutout (unit 3); plain surfaces bind a white texture here
uniform float cutout;         // > 0 discards texels whose opacity is below it
uniform vec3 color;
uniform float alpha;
uniform vec3 emissive;
uniform vec3 viewPos;
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform vec3 ambientColor;
uniform vec3 fogColor;
uniform float fogNear;
uniform float fogFar;

const vec3 SKY_COLOR = vec3(0.875, 0.914, 0.961);    // web core.js hemisphere sky (#dfe9f5)
const vec3 GROUND_COLOR = vec3(0.353, 0.329, 0.267); // web core.js hemisphere ground (#5a5444)
const float HEMI_INTENSITY = 0.85;

// Perturbs the geometric normal by a tangent-space normal map, building the tangent frame from screen
// derivatives so no tangent attributes are needed (Schueler's cotangent frame).
vec3 perturbNormal(vec3 N, vec3 P, vec2 uv, vec3 mapN) {
    vec3 dp1 = dFdx(P);
    vec3 dp2 = dFdy(P);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, N);
    vec3 dp1perp = cross(N, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    float invmax = inversesqrt(max(max(dot(T, T), dot(B, B)), 1e-12));
    mat3 TBN = mat3(T * invmax, B * invmax, N);
    return normalize(TBN * mapN);
}

void main() {
    if (cutout > 0.0 && texture(opacityTex, vUV).r < cutout) discard;

    vec3 albedo = texture(tex, vUV).rgb * color;
    vec3 arm = texture(armTex, vUV).rgb;
    float ao = arm.r;
    float roughness = arm.g;

    vec3 mapN = texture(normalTex, vUV).xyz * 2.0 - 1.0;
    vec3 N = perturbNormal(normalize(vNormal), vWorldPos, vUV, normalize(mapN));
    vec3 L = normalize(-lightDir);
    float diff = max(dot(N, L), 0.0);

    vec3 V = normalize(viewPos - vWorldPos);
    vec3 H = normalize(L + V);
    // Rough surfaces get a wide, faint highlight; smooth ones a tight, brighter one.
    float shininess = mix(96.0, 8.0, roughness);
    float spec = pow(max(dot(N, H), 0.0), shininess) * (1.0 - roughness) * 0.25 * diff;

    float sky = clamp(N.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 hemi = mix(GROUND_COLOR, SKY_COLOR, sky) * HEMI_INTENSITY;

    vec3 lit = albedo * ao * (ambientColor + hemi + lightColor * diff) + lightColor * spec + emissive;

    float dist = length(viewPos - vWorldPos);
    float fogFactor = clamp((fogFar - dist) / (fogFar - fogNear), 0.0, 1.0);
    vec3 finalColor = mix(fogColor, lit, fogFactor);

    FragColor = vec4(finalColor, alpha);
}
