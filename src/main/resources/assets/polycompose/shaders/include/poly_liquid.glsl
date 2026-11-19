#ifndef POLYCOMPOSE_POLY_LIQUID_GLSL
#define POLYCOMPOSE_POLY_LIQUID_GLSL

#ifndef PE_LIQUID_DECLARED
#ifdef PE_UNIFORM_BLOCK
layout(std140) uniform PolyLiquid {
    vec4 peLiquidA;
    vec4 peLiquidB;
    vec4 peLiquidRect[8];
    vec4 peLiquidRadius[8];
    vec4 peLiquidGlow;
    vec4 peLiquidC;
};
#else
uniform vec4 peLiquidA;
uniform vec4 peLiquidB;
uniform vec4 peLiquidRect[8];
uniform vec4 peLiquidRadius[8];
uniform vec4 peLiquidGlow;
uniform vec4 peLiquidC;
#endif
#endif

float peLiquidBox(vec2 p, vec2 halfSize, vec4 corners) {
    vec2 side = p.x > 0.0 ? corners.yz : corners.xw;
    float radius = p.y > 0.0 ? side.y : side.x;
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

vec2 peLiquidField(vec2 frag) {
    int count = int(peLiquidB.y + 0.5);
    float k = max(peLiquidB.x, 0.0) * 2.0;
    float d = 1e6;
    float limit = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= count) {
            break;
        }
        vec4 r = peLiquidRect[i];
        vec2 halfSize = max((r.zw - r.xy) * 0.5, vec2(0.0));
        vec4 corners = clamp(peLiquidRadius[i], vec4(0.0), vec4(min(halfSize.x, halfSize.y)));
        float radius = max(max(corners.x, corners.y), max(corners.z, corners.w));
        float di = peLiquidBox(frag - (r.xy + halfSize), halfSize, corners);
        if (i == 0) {
            d = di;
            limit = radius;
        } else if (k > 0.0) {
            float h = clamp(0.5 + 0.5 * (di - d) / k, 0.0, 1.0);
            d = mix(di, d, h) - k * h * (1.0 - h);
            limit = mix(radius, limit, h);
        } else if (di < d) {
            d = di;
            limit = radius;
        }
    }
    return vec2(d, limit);
}

vec4 peLiquidColor(vec4 c) {
    vec4 u = c;
    if (u.a > 0.0) {
        u.rgb /= u.a;
    }
    u = clamp(peMatrix * u + peMatrixOffset, 0.0, 1.0);
    return vec4(u.rgb * u.a, u.a);
}

vec4 peLiquid() {
    vec2 frag = PE_FRAG;
    float thickness = max(peLiquidA.x, 0.0);
    float size = clamp(peLiquidB.z, 0.0, 1.0);
    float appear = clamp(peLiquidC.x, 0.0, 1.0);
    vec2 field = peLiquidField(frag);
    float d = field.x;
    float coverage = clamp(0.5 - d, 0.0, 1.0);

    float shadowAlpha = 0.0;
    float shadowStrength = peLiquidC.z * appear;
    if (shadowStrength > 0.0 && coverage < 1.0 && thickness > 0.0) {
        float reach = max(thickness * (0.5 + 0.7 * size), 1.0);
        float drop = thickness * (0.08 + 0.12 * size);
        float ds = peLiquidField(frag - vec2(0.0, drop)).x;
        float s = 1.0 - smoothstep(0.0, reach, ds);
        shadowAlpha = clamp(shadowStrength, 0.0, 1.0) * 0.5 * s * s;
    }
    if (coverage <= 0.0) {
        return vec4(0.0, 0.0, 0.0, shadowAlpha) * peMode.x;
    }

    float bezel = min(thickness, field.y);
    float depth = -d;
    vec2 normal = vec2(0.0);
    vec2 offset = vec2(0.0);
    float tilt = 0.0;
    if (bezel > 0.5 && depth < bezel) {
        vec2 e = vec2(0.75, 0.0);
        normal = vec2(
            peLiquidField(frag + e.xy).x - peLiquidField(frag - e.xy).x,
            peLiquidField(frag + e.yx).x - peLiquidField(frag - e.yx).x
        );
        normal = normal / max(length(normal), 1e-4);
        float x = clamp(depth / bezel, 0.0, 1.0);
        float q = 1.0 - x;
        float q3 = q * q * q;
        float slope = min(q3 * pow(max(1.0 - q3 * q, 1e-4), -0.75), 24.0);
        vec3 surface = normalize(vec3(normal * slope, 1.0));
        vec3 ray = refract(vec3(0.0, 0.0, -1.0), surface, 1.0 / 1.5);
        float strength = peLiquidA.y * appear * (0.7 + 0.3 * size);
        offset = ray.xy / max(-ray.z, 0.25) * bezel * strength;
        tilt = length(surface.xy);
    }

    float spread = peLiquidA.z * tilt;
    vec4 c = peFrost(frag + offset);
    if (spread > 0.0 && tilt > 0.02) {
        c.r = peFrost(frag + offset * (1.0 + spread)).r;
        c.b = peFrost(frag + offset * (1.0 - spread)).b;
    }
    c.a = 1.0;
    c = peLiquidColor(c);

    float clearGlass = clamp(peLiquidB.w, 0.0, 1.0);
    float style = clamp(peLiquidC.y, 0.0, 1.0);
    vec3 light = mix(c.rgb, vec3(1.0), (0.08 + 0.07 * size) * appear);
    vec3 dark = mix(c.rgb, vec3(0.0), (0.14 + 0.12 * size) * appear);
    vec3 styled = mix(light, dark, style);
    vec3 plain = mix(c.rgb, vec3(1.0), 0.03 * appear);
    c.rgb = mix(styled, plain, clearGlass) * (1.0 - clamp(peLiquidC.w, 0.0, 1.0) * appear);

    if (peNoise.x > 0.0) {
        vec2 cell = floor((frag - peRect.xy) / max(peNoise.y, 1.0));
        float n = fract(sin(dot(cell, vec2(12.9898, 78.233)) + peMode.w) * 43758.5453);
        c.rgb = clamp(c.rgb + (n - 0.5) * peNoise.x, 0.0, 1.0);
    }

    float specular = max(peLiquidA.w, 0.0) * appear;
    if (specular > 0.0) {
        vec2 lightDir = vec2(-0.5, -0.8660254);
        float facing = dot(normal, lightDir);
        float rim = tilt * tilt * tilt;
        float key = max(facing, 0.0);
        float back = max(-facing, 0.0);
        float line = clamp(1.0 + d / 1.25, 0.0, 1.0) * (0.3 + 0.7 * key);
        float lit = key * key * rim * 0.55 + back * back * rim * 0.22 + line * 0.28;
        c.rgb += vec3(lit * specular);
    }

    if (peLiquidGlow.w > 0.0) {
        float g = 1.0 - smoothstep(0.0, max(peLiquidGlow.z, 1.0), length(frag - peLiquidGlow.xy));
        c.rgb += vec3(g * g * peLiquidGlow.w * (0.16 + 0.14 * tilt));
    }

    c = vec4(clamp(c.rgb, 0.0, 1.0) * coverage, coverage + shadowAlpha * (1.0 - coverage));
    return c * peMode.x;
}

#endif
