package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.pages.installed.InstalledQuery.Show;
import com.botwithus.bot.cli.gui.pages.installed.InstalledQuery.SourceFilter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.botwithus.bot.cli.gui.pages.installed.Rows.local;
import static com.botwithus.bot.cli.gui.pages.installed.Rows.run;
import static com.botwithus.bot.cli.gui.pages.installed.Rows.store;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page's filters: show, source and search, and the counts on the show segments. */
class InstalledQueryTest {

    private static final InstalledScript WOODCUTTING = store("Woodcutting", ScriptCategory.WOODCUTTING,
            run("Oakheart", RunnerState.RUNNING), run("Duskwater", RunnerState.STOPPED));
    private static final InstalledScript DIVINATION = store("Divination", ScriptCategory.DIVINATION,
            run("Fernmoss", RunnerState.STALLED));
    private static final InstalledScript COOKS = store("Cook's Assistant", ScriptCategory.QUESTING,
            run("Tamsin Vale", RunnerState.CRASHED));
    private static final InstalledScript PROBE = local("Location Probe", ScriptCategory.UTILITY,
            "utility-dev.jar", run("Kestrel Moor", RunnerState.STOPPED));
    private static final InstalledScript EXAMPLE = local("Example Script", ScriptCategory.UTILITY,
            "example-script-1.0-SNAPSHOT.jar");
    private static final List<InstalledScript> ALL = List.of(WOODCUTTING, DIVINATION, COOKS, PROBE, EXAMPLE);

    private static List<String> names(InstalledQuery q) {
        return q.apply(ALL).stream().map(InstalledScript::name).toList();
    }

    @Test
    void all_showsEverything_inOrder() {
        assertEquals(List.of("Woodcutting", "Divination", "Cook's Assistant", "Location Probe", "Example Script"),
                names(InstalledQuery.DEFAULT));
    }

    @Test
    void running_isRunningOrStalledSomewhere() {
        assertEquals(List.of("Woodcutting", "Divination"), names(InstalledQuery.DEFAULT.withShow(Show.RUNNING)));
    }

    @Test
    void notRunning_isTheRest_crashedIncluded() {
        assertEquals(List.of("Cook's Assistant", "Location Probe", "Example Script"),
                names(InstalledQuery.DEFAULT.withShow(Show.NOT_RUNNING)));
    }

    @Test
    void problems_isStalledOrCrashedSomewhere() {
        assertEquals(List.of("Divination", "Cook's Assistant"),
                names(InstalledQuery.DEFAULT.withShow(Show.PROBLEMS)));
    }

    @Test
    void source_splitsStoreFromLocalBuilds() {
        assertEquals(List.of("Woodcutting", "Divination", "Cook's Assistant"),
                names(InstalledQuery.DEFAULT.withSource(SourceFilter.STORE)));
        assertEquals(List.of("Location Probe", "Example Script"),
                names(InstalledQuery.DEFAULT.withSource(SourceFilter.LOCAL)));
    }

    @Test
    void search_matchesNameCategoryOrJar_ignoringCase() {
        assertEquals(List.of("Divination"), names(InstalledQuery.DEFAULT.withSearch("DIVIN")));
        assertEquals(List.of("Cook's Assistant"), names(InstalledQuery.DEFAULT.withSearch("quest")));
        assertEquals(List.of("Location Probe"), names(InstalledQuery.DEFAULT.withSearch("utility-dev")));
        assertEquals(List.of("Location Probe", "Example Script"),
                names(InstalledQuery.DEFAULT.withSearch("  utility ")));
    }

    @Test
    void filtersCombine() {
        InstalledQuery q = InstalledQuery.DEFAULT.withShow(Show.NOT_RUNNING).withSource(SourceFilter.LOCAL)
                .withSearch("example");
        assertEquals(List.of("Example Script"), names(q));
    }

    @Test
    void theShowSegments_countEveryScript_whateverElseIsFiltered() {
        assertEquals(5, InstalledQuery.count(Show.ALL, ALL));
        assertEquals(2, InstalledQuery.count(Show.RUNNING, ALL));
        assertEquals(3, InstalledQuery.count(Show.NOT_RUNNING, ALL));
        assertEquals(2, InstalledQuery.count(Show.PROBLEMS, ALL));
    }

    @Test
    void onlyTheDefault_hasNothingToClear() {
        assertFalse(InstalledQuery.DEFAULT.isFiltered());
        assertTrue(InstalledQuery.DEFAULT.withSearch("x").isFiltered());
        assertTrue(InstalledQuery.DEFAULT.withSource(SourceFilter.STORE).isFiltered());
    }
}
