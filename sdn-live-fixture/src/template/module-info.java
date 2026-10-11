/**
 * One SDN live-check payload. A named module, because what the check proves is
 * that a delivered jar is loaded as one, in a layer of its own.
 */
module com.botwithus.sdnlive.${variant} {
    requires com.botwithus.bot.api;

    provides com.botwithus.bot.api.BotScript with com.botwithus.sdnlive.${variant}.LiveFixtureScript;
}
