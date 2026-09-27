package com.botwithus.bot.cli.scripts;

/**
 * A script that was running on a client when a reload began.
 *
 * @param connection the client's connection name
 * @param script     the script's registered name
 */
public record RunningPair(String connection, String script) {
}
