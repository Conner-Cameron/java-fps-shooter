package com.conner.fps.render;

import com.conner.fps.engine.Shader;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A low-poly first-person weapon view-model. The caller draws this with an
 * identity view matrix (see {@code Game#render}), so it stays anchored to
 * the screen regardless of world camera rotation -- exactly how classic FPS
 * view-models work. Idle sway, a walk bob, and a brief recoil kick + muzzle
 * flash on firing keep it from feeling like a static prop bolted to the HUD.
 */
public final class GunModel {
    private static final float[] METAL_COLOR = {0.42f, 0.43f, 0.47f};
    private static final float[] ACCENT_COLOR = {0.09f, 0.09f, 0.1f};

    private static final Vector3f BASE_POSITION = new Vector3f(0.32f, -0.32f, -0.6f);

    private GunModel() {
    }

    public static void render(Shader sceneShader, CubeMesh cubeMesh, Texture metalTexture, Texture flashTexture,
                               float idleTime, float walkTime, float movingFactor,
                               float recoilTimer, float recoilDuration) {
        float idleSwayX = (float) Math.sin(idleTime * 0.6) * 0.012f;
        float idleSwayY = (float) Math.sin(idleTime * 1.1) * 0.008f;

        float walkBobX = (float) Math.sin(walkTime) * 0.02f * movingFactor;
        float walkBobY = (float) Math.abs(Math.sin(walkTime)) * 0.018f * movingFactor;

        float recoilT = Math.max(0f, recoilTimer / recoilDuration); // 1 right after firing, eases to 0
        float recoilKickZ = recoilT * 0.12f;
        float recoilPitch = (float) Math.toRadians(recoilT * 10.0);

        Vector3f pos = new Vector3f(BASE_POSITION)
                .add(idleSwayX + walkBobX, idleSwayY + walkBobY, recoilKickZ);

        Matrix4f base = new Matrix4f()
                .translate(pos)
                .rotateY((float) Math.toRadians(8.0))
                .rotateX(-recoilPitch);

        metalTexture.bind(0);
        sceneShader.setVec2("uvScale", 1f, 1f);

        drawPart(sceneShader, cubeMesh, base, 0f, -0.02f, 0.1f, 0.12f, 0.12f, 0.55f, METAL_COLOR);   // body/receiver
        drawPart(sceneShader, cubeMesh, base, 0f, 0.02f, -0.35f, 0.05f, 0.05f, 0.35f, METAL_COLOR);  // barrel
        drawPart(sceneShader, cubeMesh, base, 0f, -0.14f, 0.2f, 0.08f, 0.18f, 0.1f, ACCENT_COLOR);   // grip
        drawPart(sceneShader, cubeMesh, base, 0f, -0.1f, 0.02f, 0.06f, 0.14f, 0.22f, ACCENT_COLOR);  // magazine
        drawPart(sceneShader, cubeMesh, base, 0f, 0.02f, 0.42f, 0.09f, 0.1f, 0.22f, METAL_COLOR);    // stock

        boolean showFlash = recoilTimer > 0f && recoilTimer > recoilDuration - 0.05f;
        if (showFlash) {
            flashTexture.bind(0);
            sceneShader.setVec2("uvScale", 1f, 1f);
            Matrix4f flashModel = new Matrix4f(base).translate(0f, 0.02f, -0.56f).scale(0.16f);
            sceneShader.setMat4("model", flashModel);
            sceneShader.setMat3("normalMatrix", flashModel.normal(new Matrix3f()));
            sceneShader.setVec3("color", 3.2f, 2.6f, 0.6f);
            cubeMesh.render();
        }
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
}
