package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static com.botwithus.bot.cli.FakeAgent.olderAccountInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountReplyTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";

    @Test
    void displayName_prefersTheInGameName_thenTheLauncherNames() {
        Map<String, Object> reply = accountInfo("InGame", "FromLoader", UUID, 30, false);
        assertEquals("InGame", new AccountReply(reply).displayName().orElseThrow());

        reply.put("display_name", "");
        reply.put("jx_display_name", "FromJagex");
        assertEquals("FromJagex", new AccountReply(reply).displayName().orElseThrow());

        reply.put("jx_display_name", "");
        assertEquals("FromLoader", new AccountReply(reply).displayName().orElseThrow());
    }

    @Test
    void inGameName_isTheGamesOwnName_neverTheLauncherNames() {
        Map<String, Object> reply = accountInfo("", "FromLoader", UUID, 30, false);
        reply.put("jx_display_name", "FromJagex");
        assertEquals(Optional.empty(), new AccountReply(reply).inGameName());

        reply.put("display_name", "InGame");
        assertEquals("InGame", new AccountReply(reply).inGameName().orElseThrow());
    }

    @Test
    void launchedName_isTheJagexLaunchCharacter_neverTheGameOrLoaderName() {
        Map<String, Object> reply = accountInfo("InGame", "FromLoader", UUID, 30, false);
        assertEquals(Optional.empty(), new AccountReply(reply).launchedName());

        reply.put("jx_display_name", "FromJagex");
        assertEquals("FromJagex", new AccountReply(reply).launchedName().orElseThrow());
    }

    @Test
    void characterName_leavesOutTheLoadersAccountName() {
        AccountReply reply = new AccountReply(accountInfo("", "FromLoader", UUID, 10, false));
        assertEquals(Optional.empty(), reply.characterName());
        assertEquals("FromLoader", reply.displayName().orElseThrow());
    }

    @Test
    void displayName_emptyAccountNameIsUnknown() {
        assertEquals(Optional.empty(), new AccountReply(accountInfo("", "", UUID, 10, false)).displayName());
    }

    @Test
    void gameState_presentOnlyWhenTheAgentSendsIt() {
        assertEquals(Optional.of(GameState.LOBBY), new AccountReply(accountInfo("", "", UUID, 20, false)).gameState());
        assertEquals(Optional.of(GameState.UNKNOWN), new AccountReply(accountInfo("", "", UUID, 0, false)).gameState());
        assertTrue(new AccountReply(olderAccountInfo("", UUID, false, false)).gameState().isEmpty());
    }

    @Test
    void identifiedUuid_rejectsMissingAndDevelopmentPlaceholders() {
        assertEquals(Optional.of(UUID), AccountReply.identified(UUID));
        assertTrue(AccountReply.identified(null).isEmpty());
        assertTrue(AccountReply.identified("").isEmpty());
        assertTrue(AccountReply.identified(AccountReply.DEV_UUID).isEmpty());
        assertTrue(new AccountReply(Map.of()).identifiedUuid().isEmpty());
    }
}
