package com.conner.fps.game;

import com.conner.fps.data.Weapons;
import com.conner.fps.engine.Input;
import com.conner.fps.render.FontAtlas;
import com.conner.fps.render.Ui;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The pre-game menu flow -- mode select, loadout, mode setup -- plus the
 * pause screen, drawn with {@link Ui} and driven by the cursor. Mirrors the
 * web client's screens (and its screen-manager shape): exactly one screen is
 * showing; adding one means adding an enum value, a draw method and a route.
 * The game only hears decisions, via {@link Action}.
 */
public final class Menus {
    public enum Screen { MODE, LOADOUT, PVP_SETUP, TRAINING_SETUP, PAUSED, NONE }

    /** What the player decided this frame. */
    public static final class Action {
        public static final int NONE = 0, LOADOUT = 1, START_PVP = 2, START_TRAINING = 3, RESUME = 4, QUIT = 5, LEAVE = 6;
        public int type = NONE;
        public int weapon;
        public String name = "";
        public String server = "";
    }

    /** Screen-space rectangle (top-left origin) where a weapon preview should be rendered. */
    public static final class PreviewRect {
        public final int weapon;
        public final float x, y, w, h;

        PreviewRect(int weapon, float x, float y, float w, float h) {
            this.weapon = weapon;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final float[] YELLOW = {0.902f, 0.765f, 0.290f};
    private static final float[] YELLOW_HOVER = {0.949f, 0.820f, 0.357f};

    private Screen screen = Screen.MODE;
    private String pendingMode = "training";
    private String name = "";
    private String server;
    private int focusedField = 0; // 0 = name, 1 = server
    private final List<PreviewRect> previews = new ArrayList<>();
    private boolean resumeAvailable = false;

    public Menus(String defaultServer) {
        this.server = defaultServer;
    }

    /** Back to the first screen for a fresh mode/class choice (the typed name and server are kept). */
    public void reset() {
        screen = Screen.MODE;
        pendingMode = "training";
        focusedField = 0;
    }

    public Screen screen() {
        return screen;
    }

    public void show(Screen s) {
        screen = s;
    }

    public List<PreviewRect> previews() {
        return previews;
    }

    public String serverUrl() {
        return server;
    }

    /** Escape: step back one screen; returns true if the key was consumed by the menus. */
    public boolean back() {
        switch (screen) {
            case LOADOUT: screen = Screen.MODE; return true;
            case PVP_SETUP: case TRAINING_SETUP: screen = Screen.LOADOUT; return true;
            default: return false;
        }
    }

    /**
     * Draws the current screen and reports a decision. {@code hint} is a status line shown under setup screens
     * (e.g. connection problems).
     */
    public Action draw(Ui ui, Fonts fonts, double cursorX, double cursorY, String hint) {
        Action action = new Action();
        previews.clear();
        if (screen == Screen.NONE) return action;

        int w = ui.width(), h = ui.height();
        boolean click = Input.mousePressed[GLFW_MOUSE_BUTTON_LEFT];
        float cx = w / 2f;
        ui.rect(0, 0, w, h, 0f, 0f, 0f, 0.55f);

        switch (screen) {
            case MODE: {
                ui.textCentered(fonts.title, "Conner's PVP/Aim Training", cx, h / 2f - 150, 1, 1, 1, 1);
                ui.textCentered(fonts.body, "Choose a mode", cx, h / 2f - 92, 1, 1, 1, 0.85f);
                if (bigButton(ui, fonts, "PvP Deathmatch", "Fight other players online — first to 10 kills wins", cx - 170, h / 2f - 50, 340, 76, cursorX, cursorY)) {
                    if (click) { pendingMode = "pvp"; screen = Screen.LOADOUT; }
                }
                if (bigButton(ui, fonts, "Aim Training", "Private solo practice arena — no one else can join", cx - 170, h / 2f + 40, 340, 76, cursorX, cursorY)) {
                    if (click) { pendingMode = "training"; screen = Screen.LOADOUT; }
                }
                break;
            }
            case LOADOUT: {
                ui.textCentered(fonts.title, "Choose Your Loadout", cx, 40, 1, 1, 1, 1);
                ui.textCentered(fonts.body, "Pick your weapon — this is your loadout until you leave the game", cx, 98, 1, 1, 1, 0.85f);
                float cardW = 200, cardH = 340, gap = 14;
                float total = Weapons.LOADOUT_COUNT * cardW + (Weapons.LOADOUT_COUNT - 1) * gap;
                float x0 = cx - total / 2f, y0 = 140;
                for (int i = 0; i < Weapons.LOADOUT_COUNT; i++) {
                    float x = x0 + i * (cardW + gap);
                    boolean hover = inside(cursorX, cursorY, x, y0, cardW, cardH);
                    ui.rect(x, y0, cardW, cardH, 1, 1, 1, hover ? 0.12f : 0.06f);
                    float[] b = hover ? YELLOW : new float[]{1, 1, 1};
                    ui.rectOutline(x, y0, cardW, cardH, 2, b[0], b[1], b[2], hover ? 1f : 0.15f);
                    ui.rect(x + 25, y0 + 16, 150, 112, 0, 0, 0, 0.35f);
                    previews.add(new PreviewRect(i, x + 25, y0 + 16, 150, 112));

                    Weapons.Spec s = Weapons.ALL[i];
                    ui.textCentered(fonts.body, s.name, x + cardW / 2f, y0 + 138, 1, 1, 1, 1);
                    ui.textCentered(fonts.small, s.damage + " damage  •  " + s.magSize + " rounds", x + cardW / 2f, y0 + 162, YELLOW[0], YELLOW[1], YELLOW[2], 1);
                    float ty = y0 + 186;
                    for (String line : wrap(fonts.small, s.description, cardW - 28)) {
                        ui.textCentered(fonts.small, line, x + cardW / 2f, ty, 1, 1, 1, 0.82f);
                        ty += 17;
                    }
                    if (hover && click) {
                        action.type = Action.LOADOUT;
                        action.weapon = i;
                        screen = pendingMode.equals("pvp") ? Screen.PVP_SETUP : Screen.TRAINING_SETUP;
                    }
                }
                ui.textCentered(fonts.small, "Esc: back", cx, y0 + cardH + 24, 1, 1, 1, 0.6f);
                break;
            }
            case PVP_SETUP: {
                ui.textCentered(fonts.title, "PvP Deathmatch", cx, h / 2f - 170, 1, 1, 1, 1);
                controlsText(ui, fonts, cx, h / 2f - 108, "first to 10 kills wins the lobby");
                textField(ui, fonts, "Your name", name, cx - 140, h / 2f - 26, 280, 34, focusedField == 0, cursorX, cursorY, click, 0);
                textField(ui, fonts, "Server", server, cx - 140, h / 2f + 18, 280, 34, focusedField == 1, cursorX, cursorY, click, 1);
                editFocused();
                if (bigButtonSmall(ui, fonts, "Click to Play", cx - 90, h / 2f + 70, 180, 42, cursorX, cursorY) && click) {
                    action.type = Action.START_PVP;
                    action.name = name.trim().isEmpty() ? "Player" + (int) (Math.random() * 1000) : name.trim();
                    action.server = server.trim();
                }
                if (hint != null && !hint.isEmpty()) ui.textCentered(fonts.small, hint, cx, h / 2f + 124, 1, 0.8f, 0.5f, 1);
                ui.textCentered(fonts.small, "Esc: back", cx, h / 2f + 150, 1, 1, 1, 0.6f);
                if (Input.keyPressed[GLFW_KEY_ENTER]) {
                    action.type = Action.START_PVP;
                    action.name = name.trim().isEmpty() ? "Player" + (int) (Math.random() * 1000) : name.trim();
                    action.server = server.trim();
                }
                break;
            }
            case TRAINING_SETUP: {
                ui.textCentered(fonts.title, "Aim Training", cx, h / 2f - 120, 1, 1, 1, 1);
                controlsText(ui, fonts, cx, h / 2f - 58, "private — no other players here");
                if (bigButtonSmall(ui, fonts, "Click to Play", cx - 90, h / 2f + 10, 180, 42, cursorX, cursorY) && click) {
                    action.type = Action.START_TRAINING;
                }
                if (Input.keyPressed[GLFW_KEY_ENTER]) action.type = Action.START_TRAINING;
                ui.textCentered(fonts.small, "Esc: back", cx, h / 2f + 72, 1, 1, 1, 0.6f);
                break;
            }
            case PAUSED: {
                ui.textCentered(fonts.title, "Paused", cx, h / 2f - 110, 1, 1, 1, 1);
                if (bigButtonSmall(ui, fonts, "Resume", cx - 110, h / 2f - 60, 220, 42, cursorX, cursorY) && click) action.type = Action.RESUME;
                if (bigButtonSmall(ui, fonts, "Leave Game", cx - 110, h / 2f, 220, 42, cursorX, cursorY) && click) action.type = Action.LEAVE;
                if (bigButtonSmall(ui, fonts, "Quit", cx - 110, h / 2f + 60, 220, 42, cursorX, cursorY) && click) action.type = Action.QUIT;
                ui.textCentered(fonts.small, "Leave Game returns to mode select — pick a different mode or class", cx, h / 2f + 116, 1, 1, 1, 0.6f);
                ui.textCentered(fonts.small, "Esc: resume", cx, h / 2f + 138, 1, 1, 1, 0.6f);
                break;
            }
            default:
                break;
        }
        return action;
    }

    private void controlsText(Ui ui, Fonts fonts, float cx, float y, String tail) {
        ui.textCentered(fonts.body, "W A S D move  •  Mouse look  •  Space jump  •  Shift sprint", cx, y, 1, 1, 1, 0.9f);
        ui.textCentered(fonts.body, "Click to shoot  •  right-click to aim down sights  •  scroll to pull your knife  •  " + tail, cx, y + 24, 1, 1, 1, 0.9f);
    }

    // ------------------------------------------------------------------ widgets

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** A large yellow button with a title and a smaller subtitle; returns true while hovered. */
    private boolean bigButton(Ui ui, Fonts fonts, String title, String sub, float x, float y, float w, float h, double mx, double my) {
        boolean hover = inside(mx, my, x, y, w, h);
        float[] c = hover ? YELLOW_HOVER : YELLOW;
        ui.rect(x, y, w, h, c[0], c[1], c[2], 1);
        ui.text(fonts.medium, title, x + (w - fonts.medium.width(title)) / 2f, y + 12, 0.10f, 0.08f, 0f, 1);
        ui.text(fonts.small, sub, x + (w - fonts.small.width(sub)) / 2f, y + 42, 0.10f, 0.08f, 0f, 0.75f);
        return hover;
    }

    private boolean bigButtonSmall(Ui ui, Fonts fonts, String label, float x, float y, float w, float h, double mx, double my) {
        boolean hover = inside(mx, my, x, y, w, h);
        float[] c = hover ? YELLOW_HOVER : YELLOW;
        ui.rect(x, y, w, h, c[0], c[1], c[2], 1);
        ui.text(fonts.medium, label, x + (w - fonts.medium.width(label)) / 2f, y + (h - fonts.medium.lineHeight) / 2f, 0.10f, 0.08f, 0f, 1);
        return hover;
    }

    private void textField(Ui ui, Fonts fonts, String label, String value, float x, float y, float w, float h,
                           boolean focused, double mx, double my, boolean click, int fieldIndex) {
        if (click && inside(mx, my, x, y, w, h)) focusedField = fieldIndex;
        ui.rect(x, y, w, h, 0.05f, 0.05f, 0.06f, 0.85f);
        ui.rectOutline(x, y, w, h, 2, focused ? YELLOW[0] : 1f, focused ? YELLOW[1] : 1f, focused ? YELLOW[2] : 1f, focused ? 1f : 0.3f);
        String shown = value.isEmpty() ? label : value;
        float a = value.isEmpty() ? 0.45f : 1f;
        // keep the tail of long values visible
        while (fonts.body.width(shown) > w - 16 && shown.length() > 1) shown = shown.substring(1);
        ui.text(fonts.body, shown, x + 8, y + (h - fonts.body.lineHeight) / 2f, 1, 1, 1, a);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cxp = x + 8 + (value.isEmpty() ? 0 : fonts.body.width(shown));
            ui.rect(cxp + 1, y + 7, 2, h - 14, 1, 1, 1, 1);
        }
    }

    private void editFocused() {
        String t = Input.typed.toString();
        if (focusedField == 0) {
            name = (name + t);
            if (name.length() > 16) name = name.substring(0, 16);
            if (Input.backspacePressed && !name.isEmpty()) name = name.substring(0, name.length() - 1);
        } else {
            server = server + t;
            if (server.length() > 80) server = server.substring(0, 80);
            if (Input.backspacePressed && !server.isEmpty()) server = server.substring(0, server.length() - 1);
        }
        if (Input.keyPressed[GLFW_KEY_TAB]) focusedField = 1 - focusedField;
    }

    private static List<String> wrap(FontAtlas font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** The fonts the menus need (the game shares the HUD's atlases). */
    public static final class Fonts {
        public final FontAtlas title, medium, body, small;

        public Fonts(FontAtlas title, FontAtlas medium, FontAtlas body, FontAtlas small) {
            this.title = title;
            this.medium = medium;
            this.body = body;
            this.small = small;
        }
    }
}
