#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

layout(location = 0) in vec2 glintCoord;
layout(location = 1) in vec4 atlasCoord;
layout(location = 2) in float glintStrength;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 bytes = atlasCoord * 255.0;
    vec2 atlas = vec2(bytes.r * 256.0 + bytes.g, bytes.b * 256.0 + bytes.a) / 65535.0;
    vec3 glint = texture(Sampler1, glintCoord).rgb * glintStrength;
    fragColor = vec4(glint * glint * texture(Sampler0, atlas).a, 0.0);
}
