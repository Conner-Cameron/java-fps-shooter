package com.conner.fps.render;

/** Anything that can issue its own draw call (a mesh bound to the currently active shader). */
public interface Drawable {
    void render();
}
