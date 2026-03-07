package com.botwithus.bot.api.model;

/**
 * Account information for the current client session.
 *
 * @param clientType     the client type (0 = Jagex, 1 = Steam)
 * @param clientState    the current client state
 * @param sessionId      the session identifier
 * @param ipHash         a hash of the client's IP address
 * @param jxDisplayName  the display name from the Jagex launcher
 * @param jxCharacterId  the character ID from the Jagex launcher
 * @param displayName    the in-game display name, or {@code null} if not logged in
 * @param isMember       {@code true} if the account has membership
 * @param serverIndex    the player's server index
 * @param loggedIn       {@code true} if the player is currently logged in
 * @param loginProgress  the login progress indicator
 * @param loginStatus    the login status code
 * @see com.botwithus.bot.api.GameAPI#getAccountInfo
 */
public record AccountInfo(
        int clientType, int clientState, String sessionId,
        int ipHash, String jxDisplayName, String jxCharacterId,
        String displayName, boolean isMember, int serverIndex,
        boolean loggedIn, int loginProgress, int loginStatus
) {}
