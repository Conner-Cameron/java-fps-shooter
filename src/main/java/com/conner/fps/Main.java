package com.conner.fps;

/**
 * Entry point.
 *
 *   --server ws://host:port/ws   server the PvP mode connects to (default: the live Render deployment)
 *   --selftest NAME              run a scripted scenario (see SelfTest) instead of waiting for a person
 *   --out DIR                    where the self-test writes its screenshots
 */
public class Main {
    public static void main(String[] args) {
        String server = Game.DEFAULT_SERVER;
        String selfTest = null;
        String out = "selftest-output";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--server": server = args[i + 1]; break;
                case "--selftest": selfTest = args[i + 1]; break;
                case "--out": out = args[i + 1]; break;
                default: break;
            }
        }
        Game game = new Game(server);
        SelfTest test = selfTest != null ? new SelfTest(selfTest, out, server) : null;
        if (test != null) game.setScriptHook(test);
        game.run();
        if (test != null) System.exit(test.exitCode());
    }
}
