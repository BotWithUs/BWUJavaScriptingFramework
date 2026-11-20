package com.botwithus.bot.api.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.QuestVar;
import com.botwithus.bot.api.model.VarKind;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarpState;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Orientation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quest stage from a definition's progress variable, and the unmet-requirements walk. The
 * varp quest uses start 1 / end 7 and the varbit quest start 5 / end 40, so swapping the two
 * thresholds, comparing with the wrong operator or reading the wrong id space changes an
 * answer below.
 */
class QuestLogTest {

    private static final int VARP_QUEST = 257;
    private static final int VARBIT_QUEST = 55;
    private static final int GATED_QUEST = 111;
    private static final int NO_VARS_QUEST = 9;
    private static final int PROGRESS_VARP = 29;
    private static final int PROGRESS_VARBIT = 40_123;
    private static final int COOKING = 7;
    private static final int HERBLORE = 15;

    private final GameAPI api = mock(GameAPI.class);
    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final QuestLog quests = new QuestLog(api);

    @BeforeEach
    void setUp() {
        when(api.gamevals()).thenReturn(GamevalIndex.empty());
        when(api.snapshot()).thenReturn(snapshot);
        when(snapshot.self()).thenReturn(self(false));
        when(api.getQuestType(VARP_QUEST)).thenReturn(quest(VARP_QUEST, "Cook's Assistant", false,
                List.of(triple(PROGRESS_VARP, 1, 7)), List.of(), List.of(), List.of()));
        when(api.getQuestType(VARBIT_QUEST)).thenReturn(quest(VARBIT_QUEST, "Rune Mysteries", false,
                List.of(), List.of(triple(PROGRESS_VARBIT, 5, 40)), List.of(), List.of()));
        when(api.getQuestType(GATED_QUEST)).thenReturn(quest(GATED_QUEST, "Druidic Ritual", true,
                List.of(), List.of(), List.of(VARP_QUEST, VARBIT_QUEST),
                List.of(Map.of("skill", COOKING, "level", 12), Map.of("skill", HERBLORE, "level", 3))));
        when(api.getQuestType(NO_VARS_QUEST)).thenReturn(quest(NO_VARS_QUEST, "Odd", false,
                List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void varpQuest_atTheEndValue_isComplete() {
        varp(PROGRESS_VARP, 7);

        QuestStatus status = quests.status(VARP_QUEST);

        assertEquals(QuestStage.COMPLETE, status.stage());
        assertEquals(7, status.progress());
        assertEquals("Cook's Assistant", status.name());
        assertEquals(Optional.of(new QuestVar(QuestVar.Kind.VARP, PROGRESS_VARP, 1, 7)),
                status.variable());
        assertTrue(quests.isStarted(VARP_QUEST));
    }

    @Test
    void varpQuest_belowTheStartValue_isNotStarted_andAtIt_isStarted() {
        varp(PROGRESS_VARP, 0);
        assertEquals(QuestStage.NOT_STARTED, quests.status(VARP_QUEST).stage());

        varp(PROGRESS_VARP, 1);
        assertEquals(QuestStage.STARTED, quests.status(VARP_QUEST).stage());
        assertFalse(quests.isComplete(VARP_QUEST));
    }

    @Test
    void varbitQuest_readsTheVarbitIdSpace() {
        varbit(PROGRESS_VARBIT, 0);
        varp(PROGRESS_VARBIT, 40);

        assertEquals(QuestStage.NOT_STARTED, quests.status(VARBIT_QUEST).stage());
        assertEquals(0, quests.progress(VARBIT_QUEST));
    }

    @Test
    void unreadableVariableOrMissingDefinition_isUnknown() {
        when(api.readVarp(PROGRESS_VARP)).thenReturn(new VarpRead(PROGRESS_VARP,
                VarpState.UNAVAILABLE, -1, -1, VarKind.UNKNOWN, true));

        assertEquals(QuestStage.UNKNOWN, quests.status(VARP_QUEST).stage());
        assertEquals(QuestStatus.UNKNOWN_PROGRESS, quests.progress(VARP_QUEST));
        assertEquals(QuestStage.UNKNOWN, quests.status(NO_VARS_QUEST).stage());
        assertEquals(QuestStage.UNKNOWN, quests.status(4_242).stage());
        assertFalse(quests.isStarted(4_242));
    }

    @Test
    void unmetRequirements_listsSkillsMembershipAndIncompletePriorQuests() {
        varp(PROGRESS_VARP, 7);
        varbit(PROGRESS_VARBIT, 39);
        stat(COOKING, 11);
        stat(HERBLORE, 3);

        List<QuestRequirement> unmet = quests.unmetRequirements(GATED_QUEST);

        assertEquals(List.of(new QuestRequirement.Membership(),
                new QuestRequirement.Skill(COOKING, 12, 11),
                new QuestRequirement.PriorQuest(VARBIT_QUEST)), unmet);
        assertFalse(quests.requirementsMet(GATED_QUEST));
    }

    @Test
    void allMet_whenMemberLevelledAndPriorsComplete() {
        when(snapshot.self()).thenReturn(self(true));
        varp(PROGRESS_VARP, 7);
        varbit(PROGRESS_VARBIT, 40);
        stat(COOKING, 12);
        stat(HERBLORE, 99);

        assertTrue(quests.requirementsMet(GATED_QUEST));
    }

    private void varp(int id, int value) {
        when(api.readVarp(id)).thenReturn(new VarpRead(id, VarpState.SET, value, value,
                VarKind.INT, true));
    }

    private void varbit(int id, int value) {
        when(api.readVarbit(id)).thenReturn(new VarbitRead(id, VarpState.SET, value, true));
    }

    private void stat(int skill, int level) {
        when(api.getPlayerStat(skill)).thenReturn(new PlayerStat(skill, level, level, 0));
    }

    private static Map<String, Object> triple(int id, int start, int end) {
        return Map.of("id", id, "threshold1", start, "threshold2", end);
    }

    private static QuestType quest(int id, String name, boolean members,
                                   List<Map<String, Object>> varps,
                                   List<Map<String, Object>> varbits, List<Integer> priors,
                                   List<Map<String, Object>> skills) {
        return new QuestType(id, name, "", 0, 0, members, 1, 0, -1, List.of(), 0, priors, skills,
                varps, varbits, Map.of());
    }

    private static LocalPlayer self(boolean member) {
        return new LocalPlayer(0, 3, 3200, 3200, 0, 0, -1, -1, -1, -1, -1, member, -1,
                LocalPlayer.HEALTH_UNKNOWN, LocalPlayer.HEALTH_UNKNOWN, List.of(),
                Orientation.unknown(), LocalPlayer.RUN_UNKNOWN, LocalPlayer.RUN_UNKNOWN);
    }
}
