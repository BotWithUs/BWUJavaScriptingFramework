package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.ScriptCategory;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CategoryStyleTest {

    /**
     * {@link CategoryStyle#of} falls back to the Uncategorized style for a category
     * it has no entry for, so a newly added category would silently draw as a
     * question mark. Every category but Uncategorized must have its own.
     */
    @Test
    void of_everyCategory_hasItsOwnStyle() {
        CategoryStyle.Style fallback = CategoryStyle.of(ScriptCategory.UNCATEGORIZED);
        for (ScriptCategory category : ScriptCategory.values()) {
            if (category != ScriptCategory.UNCATEGORIZED) {
                assertNotEquals(fallback, CategoryStyle.of(category),
                        () -> category + " has no style and falls back to Uncategorized");
            }
        }
    }

    @Test
    void icon_noTwoCategoriesShareOne() {
        Map<String, ScriptCategory> owners = new HashMap<>();
        for (ScriptCategory category : ScriptCategory.values()) {
            ScriptCategory previous = owners.put(CategoryStyle.icon(category), category);
            assertNull(previous, () -> category + " reuses the icon of " + previous);
        }
    }
}
