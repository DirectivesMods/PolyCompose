#ifndef POLYCOMPOSE_POLY_EFFECT_GLSL
#define POLYCOMPOSE_POLY_EFFECT_GLSL

#ifdef PE_UNIFORM_BLOCK
layout(std140) uniform PolyEffect {
    vec4 peRect;
    vec4 peShape;
    vec4 peSource;
    vec4 peMode;
    mat4 peMatrix;
    vec4 peMatrixOffset;
    vec4 peHighlightColor;
    vec4 peHighlight;
    vec4 peNoise;
    vec4 peFallback;
    vec4 peParams[4];
};
#else
uniform vec4 peRect;
uniform vec4 peShape;
uniform vec4 peSource;
uniform vec4 peMode;
uniform mat4 peMatrix;
uniform vec4 peMatrixOffset;
uniform vec4 peHighlightColor;
uniform vec4 peHighlight;
uniform vec4 peNoise;
uniform vec4 peFallback;
uniform vec4 peParams[4];
#endif

#define polyTexel abs(peSource.zw)

vec4 polySample(vec2 uv) {
    return PE_TEX(uv);
}

float polyParam(int i) {
    int slot = i / 4;
    vec4 v = peParams[slot];
    int lane = i - slot * 4;
    if (lane == 0) {
        return v.x;
    }
    if (lane == 1) {
        return v.y;
    }
    if (lane == 2) {
        return v.z;
    }
    return v.w;
}

vec2 peUv(vec2 frag) {
    return (frag - peSource.xy) * peSource.zw;
}

float peRoundedBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

vec4 peBlur(vec2 uv, vec2 texelStep) {
    if (texelStep == vec2(0.0)) {
        return PE_TEX(uv);
    }
    vec4 c = PE_TEX(uv) * 0.19967563;
    c += (PE_TEX(uv - texelStep * 1.4073334) + PE_TEX(uv + texelStep * 1.4073334)) * 0.29732251;
    c += (PE_TEX(uv - texelStep * 3.2942150) + PE_TEX(uv + texelStep * 3.2942150)) * 0.09184834;
    c += (PE_TEX(uv - texelStep * 5.2018132) + PE_TEX(uv + texelStep * 5.2018132)) * 0.01099133;
    return c;
}

vec4 peFrost(vec2 frag) {
    float r = peMode.z;
    if (r <= 0.0) {
        return PE_TEX(peUv(frag));
    }
    float k = r / 2.5;
    float a = 2.5 * k;
    float b = 1.8 * k;
    vec4 c = PE_TEX(peUv(frag)) * 0.2;
    c += PE_TEX(peUv(frag + vec2(a, 0.0))) * 0.1;
    c += PE_TEX(peUv(frag - vec2(a, 0.0))) * 0.1;
    c += PE_TEX(peUv(frag + vec2(0.0, a))) * 0.1;
    c += PE_TEX(peUv(frag - vec2(0.0, a))) * 0.1;
    c += PE_TEX(peUv(frag + vec2(b, b))) * 0.1;
    c += PE_TEX(peUv(frag - vec2(b, b))) * 0.1;
    c += PE_TEX(peUv(frag + vec2(b, -b))) * 0.1;
    c += PE_TEX(peUv(frag - vec2(b, -b))) * 0.1;
    return c;
}

vec4 peComposite() {
    vec2 frag = PE_FRAG;
    vec2 halfSize = (peRect.zw - peRect.xy) * 0.5;
    vec2 p = frag - (peRect.xy + halfSize);
    float radius = min(peShape.x, min(halfSize.x, halfSize.y));
    float d = peRoundedBox(p, halfSize, radius);
    float coverage = clamp(0.5 - d, 0.0, 1.0) * peMode.x;
    if (coverage <= 0.0) {
        return vec4(0.0);
    }
    if (peMode.y > 0.5 && peMode.y < 1.5) {
        return peFallback * coverage;
    }

    float band = max(peShape.y, 1.0);
    float strength = peShape.z;
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    bool refracts = strength > 0.0 && t > 0.0;
    bool lit = peHighlight.x > 0.0;
    vec2 n = vec2(0.0);
    if (refracts || lit) {
        vec2 e = vec2(0.5, 0.0);
        n = vec2(
            peRoundedBox(p + e.xy, halfSize, radius) - peRoundedBox(p - e.xy, halfSize, radius),
            peRoundedBox(p + e.yx, halfSize, radius) - peRoundedBox(p - e.yx, halfSize, radius)
        );
        n = n / max(length(n), 1e-4);
    }
    float bend = 0.0;
    vec2 offset = vec2(0.0);
    if (refracts) {
        bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
        offset = n * band * strength * bend;
    }
    float spread = peShape.w * bend;

    vec4 c = peFrost(frag + offset);
    if (bend > 0.02 && spread > 0.0) {
        c.r = peFrost(frag + offset * (1.0 + spread)).r;
        c.b = peFrost(frag + offset * (1.0 - spread)).b;
    }
    if (peMode.y < 0.5) {
        c.a = 1.0;
    }

    vec4 u = c;
    if (u.a > 0.0) {
        u.rgb /= u.a;
    }
    u = clamp(peMatrix * u + peMatrixOffset, 0.0, 1.0);
    c = vec4(u.rgb * u.a, u.a);

    if (peNoise.x > 0.0) {
        vec2 cell = floor((frag - peRect.xy) / max(peNoise.y, 1.0));
        float h = fract(sin(dot(cell, vec2(12.9898, 78.233)) + peMode.w) * 43758.5453);
        c.rgb = clamp(c.rgb + (h - 0.5) * peNoise.x * c.a, 0.0, 1.0);
    }

    if (lit) {
        float rim = clamp(1.0 + d / peHighlight.x, 0.0, 1.0);
        float facing = max(dot(n, peHighlight.yz), 0.0);
        c = clamp(c + peHighlightColor * (rim * facing), 0.0, 1.0);
    }

    return c * coverage;
}

#ifdef PE_CUSTOM
vec4 polyEffect(vec2 uv, vec2 px);

vec4 peCustom() {
    vec2 frag = PE_FRAG;
    return polyEffect(peUv(frag), frag - peRect.xy);
}
#endif

#endif
