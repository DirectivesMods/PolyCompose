#version 330

#define PE_UNIFORM_BLOCK

uniform sampler2D Source;

layout(std140) uniform PolyTarget {
    vec4 ptSize;
    vec4 ptMap;
    vec4 ptClamp;
    vec4 ptClip;
};

in vec2 texCoord;

out vec4 fragColor;

vec2 ptFrag() {
    return ptMap.xy + texCoord * ptSize.xy * ptMap.zw;
}

#define PE_SOURCE Source
#define PE_TEX(uv) textureLod(Source, clamp(uv, ptClamp.xy, ptClamp.zw), 0.0)
#define PE_FRAG ptFrag()

#moj_import <polycompose:poly_effect.glsl>
#moj_import <polycompose:poly_liquid.glsl>

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
