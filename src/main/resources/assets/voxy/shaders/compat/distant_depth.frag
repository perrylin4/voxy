#version 460 core

layout(binding = 0) uniform sampler2D uAtlas;
layout(location = 0) in vec2 fUv;

void main() {
    if (texture(uAtlas, fUv).a < 0.1) {
        discard;
    }
}
