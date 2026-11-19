#version 330
#extension GL_ARB_separate_shader_objects : require

#define PE_UNIFORM_BLOCK

uniform sampler2D Source;

layout(std140) uniform PolyTarget {
    vec4 ptSize;
    vec4 ptMap;
    vec4 ptClamp;
    vec4 ptClip;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec2 ptFrag() {
    return ptMap.xy + texCoord * ptSize.xy * ptMap.zw;
}

#define PE_SOURCE Source
#define PE_TEX(uv) textureLod(Source, clamp(uv, ptClamp.xy, ptClamp.zw), 0.0)
#define PE_FRAG ptFrag()

#include <polycompose:poly_effect.glsl>
#include <polycompose:poly_liquid.glsl>

void main() {
    vec2 frag = PE_FRAG;
    if (frag.x < ptClip.x || frag.y < ptClip.y || frag.x >= ptClip.z || frag.y >= ptClip.w) {
        discard;
    }
    vec4 c = peMode.y > 2.5 ? peLiquid() : peComposite();
    if (c.a <= 0.0) {
        discard;
    }
    fragColor = c;
}
