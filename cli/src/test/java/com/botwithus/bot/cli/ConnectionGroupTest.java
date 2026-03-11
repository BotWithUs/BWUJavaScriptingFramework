package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionGroupTest {

    @Test
    void nameIsPreserved() {
        var group = new ConnectionGroup("farm");
        assertEquals("farm", group.getName());
    }

    @Test
    void addAndContains() {
        var group = new ConnectionGroup("farm");
        assertFalse(group.contains("BotWithUs"));

        group.add("BotWithUs");
        assertTrue(group.contains("BotWithUs"));
        assertEquals(1, group.getConnectionNames().size());
    }

    @Test
    void addDuplicateIsIdempotent() {
        var group = new ConnectionGroup("farm");
        group.add("BotWithUs");
        group.add("BotWithUs");
        assertEquals(1, group.getConnectionNames().size());
    }

    @Test
    void remove() {
        var group = new ConnectionGroup("farm");
        group.add("BotWithUs");
        group.add("BotWithUs2");
        assertEquals(2, group.getConnectionNames().size());

        group.remove("BotWithUs");
        assertFalse(group.contains("BotWithUs"));
        assertTrue(group.contains("BotWithUs2"));
        assertEquals(1, group.getConnectionNames().size());
    }

    @Test
    void removeNonExistentIsNoOp() {
        var group = new ConnectionGroup("farm");
        group.remove("nope"); // should not throw
        assertEquals(0, group.getConnectionNames().size());
    }

    @Test
    void getConnectionNamesIsUnmodifiable() {
        var group = new ConnectionGroup("farm");
        group.add("BotWithUs");
        assertThrows(UnsupportedOperationException.class, () ->
                group.getConnectionNames().add("illegal"));
    }

    @Test
    void constructorWithDescription() {
        var group = new ConnectionGroup("farm", "Farming accounts");
        assertEquals("farm", group.getName());
        assertEquals("Farming accounts", group.getDescription());
    }

    @Test
    void descriptionIsNullByDefault() {
        var group = new ConnectionGroup("farm");
        assertNull(group.getDescription());
    }

    @Test
    void setDescription() {
        var group = new ConnectionGroup("farm");
        assertNull(group.getDescription());
        group.setDescription("Skilling bots");
        assertEquals("Skilling bots", group.getDescription());
    }

    @Test
    void setDescriptionOverwrites() {
        var group = new ConnectionGroup("farm", "old");
        group.setDescription("new");
        assertEquals("new", group.getDescription());
    }

    @Test
    void preservesInsertionOrder() {
        var group = new ConnectionGroup("farm");
        group.add("C");
        group.add("A");
        group.add("B");
        var names = group.getConnectionNames().stream().toList();
        assertEquals("C", names.get(0));
        assertEquals("A", names.get(1));
        assertEquals("B", names.get(2));
    }
}
