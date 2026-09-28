#version 330 core
in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUV;
out vec4 FragColor;

uniform sampler2D baseMap;
uniform sampler2D normalMap;
uniform sampler2D mrMap;
uniform int hasNormal;
uniform int hasMR;
uniform vec4 baseFactor;
uniform float metallicFactor;
uniform float roughnessFactor;
uniform float normalScale;

uniform vec3 viewPos;
uniform vec3 lightDir;
uniform vec3 lightColor;
uniform vec3 ambientColor;
uniform vec3 skyTop;
uniform vec3 skyBottom;
uniform vec3 fogColor;
uniform float fogNear;
uniform float fogFar;

const float PI = 3.14159265;

// A neutral studio-ish environment (bright sky above, dim ground below) that metallic
// surfaces reflect -- without something to reflect, metals render black.
vec3 environment(vec3 dir) {
    float h = dir.y;
    vec3 sky = mix(skyBottom, skyTop, clamp(h, 0.0, 1.0));
    vec3 ground = skyBottom * 0.28;
    return mix(ground, sky, smoothstep(-0.15, 0.15, h)) * 0.95;
}

vec3 perturbNormal(vec3 N) {
    vec3 dp1 = dFdx(vWorldPos);
    vec3 dp2 = dFdy(vWorldPos);
    vec2 duv1 = dFdx(vUV);
    vec2 duv2 = dFdy(vUV);
    vec3 dp2perp = cross(dp2, N);
    vec3 dp1perp = cross(N, dp1);
    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
    float invmax = inversesqrt(max(dot(T, T), dot(B, B)));
    mat3 TBN = mat3(T * invmax, B * invmax, N);
    vec3 n = texture(normalMap, vUV).xyz * 2.0 - 1.0;
    n.xy *= normalScale;
    n.y = -n.y; // glTF normal maps are +Y up; glTF UVs run downward
    return normalize(TBN * n);
}

void main() {
    vec4 baseSample = texture(baseMap, vUV) * baseFactor;
    vec3 base = baseSample.rgb;
    float metallic = metallicFactor;
    float rough = roughnessFactor;
    if (hasMR == 1) {
        vec3 mr = texture(mrMap, vUV).rgb;
        rough *= mr.g;
        metallic *= mr.b;
    }
    rough = clamp(rough, 0.06, 1.0);

    vec3 N = normalize(vNormal);
    if (hasNormal == 1) N = perturbNormal(N);
    if (!gl_FrontFacing) N = -N;
    vec3 V = normalize(viewPos - vWorldPos);
    vec3 L = normalize(-lightDir);
    vec3 H = normalize(L + V);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0001);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);

    vec3 F0 = mix(vec3(0.04), base, metallic);
    vec3 diffuseColor = base * (1.0 - metallic);

    // GGX specular for the sun
    float a = rough * rough;
    float a2 = a * a;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * d * d);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = (NdotV / (NdotV * (1.0 - k) + k)) * (NdotL / (NdotL * (1.0 - k) + k));
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
    vec3 specular = D * G * F / max(4.0 * NdotV * NdotL, 0.001);

    vec3 direct = (diffuseColor + specular) * lightColor * NdotL;

    // Environment reflection (Schlick with roughness), plus flat ambient for the diffuse part
    vec3 R = reflect(-V, N);
    vec3 Fr = F0 + (max(vec3(1.0 - rough), F0) - F0) * pow(1.0 - NdotV, 5.0);
    vec3 ibl = Fr * environment(R) * (1.0 - rough * 0.6);
    vec3 lit = diffuseColor * ambientColor + direct + ibl;

    float dist = length(viewPos - vWorldPos);
    float fogFactor = clamp((fogFar - dist) / (fogFar - fogNear), 0.0, 1.0);
    FragColor = vec4(mix(fogColor, lit, fogFactor), 1.0);
}
