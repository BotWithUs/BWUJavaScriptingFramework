package com.botwithus.bot.cli.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.StoreShelf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers delivered Store scripts on a client, giving each client its own instance.
 *
 * <p>A script object holds per-run state, so the one instance a delivery yields must
 * not be registered on two clients. Each registration takes a new instance off the
 * {@link StoreShelf} instead. A delivered script the shelf does not hold, which only
 * an installer built without the host's shelf produces, is registered as delivered.
 */
public final class StoreRegistrar {

    private final StoreShelf shelf;

    /** @param shelf the host's shelf, the one its installer stocks */
    public StoreRegistrar(StoreShelf shelf) {
        this.shelf = shelf;
    }

    /** The shelf copies are taken from. */
    public StoreShelf shelf() {
        return shelf;
    }

    /** Registers a new instance of each of {@code delivered} on {@code runtime}. */
    public void registerOn(ScriptRuntime runtime, List<BotScript> delivered) {
        copiesOf(delivered).forEach(runtime::registerScript);
    }

    /** A new instance of each of {@code delivered}, in its order where the shelf can make one. */
    public List<BotScript> copiesOf(List<BotScript> delivered) {
        Set<String> classes = delivered.stream().map(s -> s.getClass().getName()).collect(Collectors.toSet());
        List<BotScript> copies = new ArrayList<>(shelf.freshCopiesOf(classes));
        Set<String> shelved = shelf.classes();
        delivered.stream().filter(s -> !shelved.contains(s.getClass().getName())).forEach(copies::add);
        return copies;
    }
}
