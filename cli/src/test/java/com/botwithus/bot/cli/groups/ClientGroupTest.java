package com.botwithus.bot.cli.groups;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientGroupTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String PIPE = "BotWithUs_1001";

    @Test
    void membersKeepTheirOrder_withoutRepeats() {
        ClientGroup group = ClientGroup.create("farm", Optional.empty())
                .withMember(UUID_B).withMember(UUID_A).withMember(UUID_B);

        assertEquals(List.of(UUID_B, UUID_A), group.members());
    }

    @Test
    void theMemberListCannotBeChangedFromOutside() {
        ClientGroup group = ClientGroup.create("farm", Optional.empty()).withMember(UUID_A);

        assertThrows(UnsupportedOperationException.class, () -> group.members().add(UUID_B));
    }

    @Test
    void aBlankDescription_isNoDescription() {
        assertEquals(Optional.empty(), ClientGroup.create("farm", Optional.of("  ")).description());
    }

    @Test
    void aBlankName_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientGroup.create(" ", Optional.empty()));
    }

    @Test
    void resolvingAPipe_swapsTheUnresolvedMemberForItsAccount() {
        ClientGroup group = new ClientGroup(GroupId.random(), "farm", Optional.empty(), List.of(UUID_A),
                List.of(PIPE), Optional.empty());

        ClientGroup resolved = group.resolving(PIPE, UUID_B);

        assertEquals(List.of(UUID_A, UUID_B), resolved.members());
        assertTrue(resolved.unresolved().isEmpty());
        assertSame(group, group.resolving("BotWithUs_other", UUID_B), "another pipe changes nothing");
    }

    @Test
    void aMigratedGroupsId_dependsOnlyOnItsName() {
        assertEquals(GroupId.migratedFrom("farm"), GroupId.migratedFrom("farm"));
        assertNotEquals(GroupId.migratedFrom("farm"), GroupId.migratedFrom("bosses"));
        assertEquals(Optional.of(GroupId.migratedFrom("farm")),
                GroupId.parse(GroupId.migratedFrom("farm").toString()));
    }
}
