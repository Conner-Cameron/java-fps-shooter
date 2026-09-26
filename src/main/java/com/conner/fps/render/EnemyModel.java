package com.conner.fps.render;

import com.conner.fps.engine.Shader;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a blocky humanoid "generic enemy" -- boots, legs, torso, shoulder
 * plates, arms, hands, and a head -- built entirely from the same unit
 * CubeMesh used for everything else in the scene, now lit and textured
 * instead of flat-colored. Arm swing / body sway / head glance are layered
 * on top of the target's own bob/wander (see
 * {@link com.conner.fps.world.Target}) so it reads as alive rather than a
 * static prop, without needing an actual model file or animation rig.
 */
public final class EnemyModel {
    private static final float[] ARMOR_COLOR = {0.62f, 0.64f, 0.68f};
    private static final float[] TRIM_COLOR = {0.12f, 0.12f, 0.14f};
    private static final float[] HEAD_COLOR = {0.92f, 0.15f, 0.15f};

    private EnemyModel() {
    }

    public static void render(Shader sceneShader, CubeMesh cubeMesh, Texture armorTexture, Texture accentTexture,
                               Vector3f position, float animTime) {
        float sway = (float) Math.toRadians(Math.sin(animTime * 0.7) * 6.0);
        float armSwing = (float) Math.toRadians(Math.sin(animTime * 2.2) * 18.0);
        float headTurn = (float) Math.toRadians(Math.sin(animTime * 0.9) * 10.0);

        Matrix4f base = new Matrix4f().translate(position).rotateY(sway);

        armorTexture.bind(0);
        sceneShader.setVec2("uvScale", 1f, 1f);

        // Boots
        drawPart(sceneShader, cubeMesh, base, -0.15f, -0.82f, 0.02f, 0.3f, 0.18f, 0.34f, TRIM_COLOR);
        drawPart(sceneShader, cubeMesh, base, 0.15f, -0.82f, 0.02f, 0.3f, 0.18f, 0.34f, TRIM_COLOR);

        // Legs
        drawPart(sceneShader, cubeMesh, base, -0.15f, -0.45f, 0f, 0.26f, 0.6f, 0.26f, ARMOR_COLOR);
        drawPart(sceneShader, cubeMesh, base, 0.15f, -0.45f, 0f, 0.26f, 0.6f, 0.26f, ARMOR_COLOR);

        // Torso + belt trim
        drawPart(sceneShader, cubeMesh, base, 0f, 0.22f, 0f, 0.6f, 0.65f, 0.34f, ARMOR_COLOR);
        drawPart(sceneShader, cubeMesh, base, 0f, -0.1f, 0f, 0.62f, 0.1f, 0.36f, TRIM_COLOR);

        // Shoulder plates
        drawPart(sceneShader, cubeMesh, base, -0.4f, 0.52f, 0f, 0.2f, 0.16f, 0.38f, TRIM_COLOR);
        drawPart(sceneShader, cubeMesh, base, 0.4f, 0.52f, 0f, 0.2f, 0.16f, 0.38f, TRIM_COLOR);

        // Arms + hands, swinging about the shoulder, opposite phase from each other
        drawArm(sceneShader, cubeMesh, base, -0.42f, 0.46f, armSwing);
        drawArm(sceneShader, cubeMesh, base, 0.42f, 0.46f, -armSwing);

        // Head -- kept a solid glowing red so it still reads as "shoot here" at a glance
        accentTexture.bind(0);
        sceneShader.setVec2("uvScale", 1f, 1f);
        Matrix4f headModel = new Matrix4f(base)
                .translate(0f, 0.72f, 0f)
                .rotateY(headTurn)
                .scale(0.36f, 0.34f, 0.36f);
        sceneShader.setMat4("model", headModel);
        sceneShader.setMat3("normalMatrix", headModel.normal(new Matrix3f()));
        sceneShader.setVec3("color", HEAD_COLOR[0], HEAD_COLOR[1], HEAD_COLOR[2]);
        cubeMesh.render();
    }

    private static void drawPart(Shader sceneShader, CubeMesh cubeMesh, Matrix4f base,
                                  float offsetX, float offsetY, float offsetZ,
                                  float sizeX, float sizeY, float sizeZ, float[] color) {
        Matrix4f model = new Matrix4f(base).translate(offsetX, offsetY, offsetZ).scale(sizeX, sizeY, sizeZ);
        sceneShader.setMat4("model", model);
        sceneShader.setMat3("normalMatrix", model.normal(new Matrix3f()));
        sceneShader.setVec3("color", color[0], color[1], color[2]);
        cubeMesh.render();
    }

    private static void drawArm(Shader sceneShader, CubeMesh cubeMesh, Matrix4f base,
                                 float shoulderX, float shoulderY, float swingAngle) {
        Matrix4f pivot = new Matrix4f(base).translate(shoulderX, shoulderY, 0f).rotateX(swingAngle);

        Matrix4f armModel = new Matrix4f(pivot).translate(0f, -0.25f, 0f).scale(0.18f, 0.46f, 0.18f);
        sceneShader.setMat4("model", armModel);
        sceneShader.setMat3("normalMatrix", armModel.normal(new Matrix3f()));
        sceneShader.setVec3("color", ARMOR_COLOR[0], ARMOR_COLOR[1], ARMOR_COLOR[2]);
        cubeMesh.render();

        Matrix4f handModel = new Matrix4f(pivot).translate(0f, -0.52f, 0f).scale(0.16f, 0.14f, 0.16f);
        sceneShader.setMat4("model", handModel);
        sceneShader.setMat3("normalMatrix", handModel.normal(new Matrix3f()));
        sceneShader.setVec3("color", TRIM_COLOR[0], TRIM_COLOR[1], TRIM_COLOR[2]);
        cubeMesh.render();
    }
}
