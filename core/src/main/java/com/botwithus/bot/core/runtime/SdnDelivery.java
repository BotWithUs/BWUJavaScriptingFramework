package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import java.util.List;

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
     * @param scripts  its providers; never empty
     */
    public record Jar(int position, List<BotScript> scripts) {

        public Jar {
            scripts = List.copyOf(scripts);
        }
    }
}
