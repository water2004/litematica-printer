package me.aleksilassila.litematica.printer.gametest;

final class GameTestMode {
    static final String BEDROCK_MINER_PROPERTY =
            "litematica-printer.gametest.bedrockMiner";

    private GameTestMode() {
    }

    static String bedrockMiner() {
        return System.getProperty(BEDROCK_MINER_PROPERTY, "none");
    }

    static boolean isBedrockIntegration() {
        return !bedrockMiner().equals("none");
    }

    static boolean isScanPerformance() {
        return Boolean.getBoolean("litematica-printer.gametest.scanPerformance");
    }
}
