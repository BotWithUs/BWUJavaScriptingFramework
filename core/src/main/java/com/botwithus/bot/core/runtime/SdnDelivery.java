package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import java.util.List;
import java.util.function.Supplier;

/**
 * The scripts one courier delivery yielded, grouped by the jar they came from.
 *
 * <p>Kept per jar because the jar, not a class or manifest name, is what says
 * which requested script a provider is: the launcher delivers one jar per
 * requested id, in the order requested. A class name cannot say it, because a
 * catalogue entry shared by both agents names the agent-v1 class while the jar
 * delivered here is the v2 build.
 *
 * @param jarCount how many jars the delivery carried, including any that failed
 *                 to define or yielded no provider
 * @param jars     the jars that yielded at least one provider, in delivery order
 */
public record SdnDelivery(int jarCount, List<Jar> jars) {

    public SdnDelivery {
        jars = List.copyOf(jars);
    }

    /** Nothing arrived. */
    public static SdnDelivery none() {
        return new SdnDelivery(0, List.of());
    }

    /** Every delivered script, in delivery order. */
    public List<BotScript> scripts() {
        return jars.stream().flatMap(jar -> jar.scripts().stream()).toList();
    }

    /** True when no script arrived. */
    public boolean isEmpty() {
        return jars.isEmpty();
    }

    /**
     * The providers one jar yielded.
     *
     * @param position the jar's zero-based place in the delivery
     * @param scripts  one instance of each of its providers; never empty
     * @param copies   one factory per entry of {@code scripts}, in the same order,
     *                 each making a new instance of that provider. This is what
     *                 gives every client its own instance, and lets a client that
     *                 connects later, or a reload, get the script back without a
     *                 second delivery. It holds the jar's loader, in memory only.
     */
    public record Jar(int position, List<BotScript> scripts, List<Supplier<BotScript>> copies) {

        public Jar {
            scripts = List.copyOf(scripts);
            copies = List.copyOf(copies);
            if (copies.size() != scripts.size()) {
                throw new IllegalArgumentException("one copy factory per script: "
                        + copies.size() + " for " + scripts.size());
            }
        }

        /**
         * A jar whose scripts can only be handed out as the instances given: each
         * "copy" is that same object. For tests and callers with no provider to
         * call again; a real delivery uses the canonical constructor.
         */
        public Jar(int position, List<BotScript> scripts) {
            this(position, scripts, scripts.stream().<Supplier<BotScript>>map(s -> () -> s).toList());
        }
    }
}
