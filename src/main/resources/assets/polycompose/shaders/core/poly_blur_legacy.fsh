#version 330

uniform sampler2D Source;

layout(std140) uniform PolyBlur {
    vec4 pbSize;
    vec4 pbMap;
    vec4 pbStep;
    vec4 pbClamp;
};

in vec2 texCoord;

out vec4 fragColor;

vec4 tap(vec2 uv) {
    return textureLod(Source, clamp(uv, pbClamp.xy, pbClamp.zw), 0.0);
}

void main() {
    vec2 uv = texCoord * pbSize.xy * pbMap.xy + pbMap.zw;
    vec2 s = pbStep.xy;
    if (s == vec2(0.0)) {
        fragColor = tap(uv);
        return;
    }
    vec4 c = tap(uv) * 0.19967563;
    c += (tap(uv - s * 1.4073334) + tap(uv + s * 1.4073334)) * 0.29732251;
    c += (tap(uv - s * 3.2942150) + tap(uv + s * 3.2942150)) * 0.09184834;
    c += (tap(uv - s * 5.2018132) + tap(uv + s * 5.2018132)) * 0.01099133;
    fragColor = c;
}
