package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillTypeTest {

    private static final int SKILL_COUNT = 29;

    @Test
    void ids_areContiguousFromZero_andMatchTheSkillsConstants() {
        SkillType[] all = SkillType.values();
        assertEquals(SKILL_COUNT, all.length);
        for (int i = 0; i < all.length; i++) {
            assertEquals(i, all[i].id(), all[i].name());
        }
        assertEquals(Skills.NECROMANCY, SkillType.NECROMANCY.id());
        assertEquals(Skills.INVENTION, SkillType.INVENTION.id());
    }

    @ParameterizedTest
    @EnumSource(SkillType.class)
    void byId_roundTrips(SkillType skill) {
        assertEquals(Optional.of(skill), SkillType.byId(skill.id()));
    }

    @Test
    void byId_unknownId_isEmpty() {
        assertEquals(Optional.empty(), SkillType.byId(SKILL_COUNT));
        assertEquals(Optional.empty(), SkillType.byId(-1));
    }

    @Test
    void onlyInvention_isElite() {
        for (SkillType skill : SkillType.values()) {
            XpTable expected = skill == SkillType.INVENTION ? XpTable.ELITE : XpTable.STANDARD;
            assertSame(expected, skill.xpTable(), skill.name());
        }
    }

    @Test
    void skillsHelpers_readThroughGetPlayerStat() {
        GameAPI api = mock(GameAPI.class);
        int xp = 1_000;
        when(api.getPlayerStat(Skills.WOODCUTTING))
                .thenReturn(new PlayerStat(Skills.WOODCUTTING, 9, 10, xp));

        assertEquals(9, Skills.getLevel(api, SkillType.WOODCUTTING));
        assertEquals(10, Skills.getBoostedLevel(api, SkillType.WOODCUTTING));
        assertEquals(xp, Skills.getXp(api, SkillType.WOODCUTTING));
        assertEquals(XpTable.STANDARD.xpForLevel(10) - xp,
                Skills.xpToNextLevel(api, SkillType.WOODCUTTING));
        assertEquals(9, Skills.getVirtualLevel(api, SkillType.WOODCUTTING));
    }

    @Test
    void skillsHelpers_noStat_readZero() {
        GameAPI api = mock(GameAPI.class);
        assertEquals(0, Skills.xpToNextLevel(api, SkillType.MINING));
        assertEquals(0, Skills.getVirtualLevel(api, SkillType.MINING));
    }
}
