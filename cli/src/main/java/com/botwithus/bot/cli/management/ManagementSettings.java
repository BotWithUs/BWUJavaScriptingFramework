package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.core.config.ManagementSettingsStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A management script's settings per target, and the order they are inherited in.
 *
 * <p>A value is taken from the first of these that sets it: the client
 * script's own values, then those of a group the client is in, if that group
 * is also one of the script's targets, then the script's defaults. A group
 * inherits straight from the defaults, and a script that manages the whole host
 * uses only the defaults. A target keeps only the values that differ from what
 * it would inherit: applying a value equal to the inherited one removes it.</p>
 *
 * <p>Only the script's current targets count. A target that is removed keeps
 * its values on disk, so adding it back brings them back, but nothing inherits
 * them meanwhile.</p>
 *
 * <p>The defaults are what {@code onConfigUpdate} receives; they are applied
 * through the script's runner, which saves them in the same store.</p>
 */
public final class ManagementSettings {

    /** Where a value a target does not set itself comes from. */
    public sealed interface Source {

        /** The script's defaults. */
        record Defaults() implements Source { }

        /** A group target the client is a member of. */
        record FromGroup(GroupId id) implements Source {
            public FromGroup {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    /**
     * The value a target gets for a field when it has none of its own.
     *
     * @param value  the value
     * @param source where it comes from
     */
    public record Inherited(String value, Source source) {
        public Inherited {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * One target's settings.
     *
     * @param merged    what the target gets: its own values over the inherited ones
     * @param own       the values the target sets itself
     * @param inherited for each declared field, what the target would get without its own value
     */
    public record TargetView(ScriptConfig merged, Map<String, String> own, Map<String, Inherited> inherited) {
        public TargetView {
            own = Map.copyOf(own);
            inherited = Map.copyOf(inherited);
        }
    }

    /** One place values are inherited from, and the values it sets. */
    private record Layer(Source source, Map<String, String> values) { }

    private final ManagementSettingsStore store;
    private final ManagementTargets targets;
    private final GroupStore groups;

    public ManagementSettings(ManagementSettingsStore store, ManagementTargets targets, GroupStore groups) {
        this.store = Objects.requireNonNull(store, "store");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    /** {@code script}'s defaults: its declared defaults overlaid with the saved ones. */
    public ScriptConfig defaults(String script, List<ConfigField> fields) {
        return store.defaults(script, fields);
    }

    /** {@code target}'s settings for {@code script}, with where each inherited value comes from. */
    public TargetView view(String script, Target target, List<ConfigField> fields) {
        Map<String, Inherited> inherited = inheritedFor(script, target, fields);
        Map<String, String> own = ownValues(script, target);
        Map<String, String> merged = new LinkedHashMap<>(store.defaults(script, fields).asMap());
        inherited.forEach((key, value) -> merged.put(key, value.value()));
        merged.putAll(own);
        return new TargetView(new ScriptConfig(merged), own, inherited);
    }

    /**
     * Whether {@code target} has a group to inherit from before the defaults:
     * a client script in a group that is also one of {@code script}'s targets.
     */
    public boolean inheritsFromAGroup(String script, Target target) {
        return switch (target) {
            case Target.ClientScript cs -> !groupLayers(script, cs.accountUuid()).isEmpty();
            case Target.Group _, Target.Host _ -> false;
        };
    }

    /** How many values {@code target} sets itself for {@code script}. */
    public int ownCount(String script, Target target) {
        return ownValues(script, target).size();
    }

    /**
     * Makes {@code values} {@code target}'s settings for {@code script}. For each
     * declared field, a value equal to the one the target would inherit is not
     * kept, so the target follows its group or the defaults again; a field
     * {@code values} leaves out keeps what it had. A value kept for a field the
     * script no longer declares is dropped.
     *
     * @throws IllegalArgumentException for the whole host, which uses the
     *                                  defaults; apply those through the runner
     */
    public void apply(String script, Target target, ScriptConfig values, List<ConfigField> fields) {
        switch (target) {
            case Target.Host _ -> throw new IllegalArgumentException("The whole host uses the defaults");
            case Target.Group _, Target.ClientScript _ -> saveOwn(script, target, values, fields);
        }
    }

    private void saveOwn(String script, Target target, ScriptConfig values, List<ConfigField> fields) {
        Map<String, Inherited> inherited = inheritedFor(script, target, fields);
        Map<String, String> own = new LinkedHashMap<>();
        Map<String, String> before = store.overrides(script, target.key());
        for (ConfigField field : fields) {
            String key = field.key();
            String value = values.asMap().getOrDefault(key, before.get(key));
            if (value != null && !value.equals(inherited.get(key).value())) {
                own.put(key, value);
            }
        }
        store.saveOverrides(script, target.key(), own);
    }

    /**
     * {@code script}'s settings for the client on {@code accountUuid}: the own
     * values of its client-script targets on that account, the first added
     * first, then its groups', then the defaults.
     */
    public ScriptConfig configFor(String script, List<ConfigField> fields, String accountUuid) {
        return merge(script, fields, accountUuid, cs -> true);
    }

    /** As {@link #configFor(String, List, String)}, with only {@code scriptName}'s own values first. */
    public ScriptConfig configFor(String script, List<ConfigField> fields, String accountUuid, String scriptName) {
        return merge(script, fields, accountUuid, cs -> cs.scriptName().equals(scriptName));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private ScriptConfig merge(String script, List<ConfigField> fields, String accountUuid,
                               Predicate<Target.ClientScript> isWanted) {
        List<Map<String, String>> firstWins = new ArrayList<>();
        for (Target.ClientScript cs : clientScriptTargets(script, accountUuid)) {
            if (isWanted.test(cs)) {
                firstWins.add(store.overrides(script, cs.key()));
            }
        }
        groupLayers(script, accountUuid).forEach(layer -> firstWins.add(layer.values()));
        Map<String, String> merged = new LinkedHashMap<>(store.defaults(script, fields).asMap());
        for (Map<String, String> values : firstWins.reversed()) {
            merged.putAll(values);
        }
        return new ScriptConfig(merged);
    }

    /** For each declared field, what {@code target} gets when it has no value of its own. */
    private Map<String, Inherited> inheritedFor(String script, Target target, List<ConfigField> fields) {
        List<Layer> layers = switch (target) {
            case Target.ClientScript cs -> groupLayers(script, cs.accountUuid());
            case Target.Group _, Target.Host _ -> List.of();
        };
        Map<String, String> defaults = store.defaults(script, fields).asMap();
        Map<String, Inherited> inherited = new LinkedHashMap<>();
        for (ConfigField field : fields) {
            String key = field.key();
            inherited.put(key, layers.stream()
                    .filter(layer -> layer.values().containsKey(key))
                    .findFirst()
                    .map(layer -> new Inherited(layer.values().get(key), layer.source()))
                    .orElseGet(() -> new Inherited(defaults.get(key), new Source.Defaults())));
        }
        return inherited;
    }

    /** The group targets of {@code script} that {@code accountUuid} is in, oldest group first. */
    private List<Layer> groupLayers(String script, String accountUuid) {
        Set<GroupId> targeted = targets.targetsOf(script).stream()
                .flatMap(target -> groupOf(target).stream())
                .collect(Collectors.toSet());
        return groups.all().stream()
                .filter(group -> targeted.contains(group.id()) && group.contains(accountUuid))
                .map(ClientGroup::id)
                .map(id -> new Layer(new Source.FromGroup(id), store.overrides(script, new Target.Group(id).key())))
                .toList();
    }

    private static Optional<GroupId> groupOf(Target target) {
        return switch (target) {
            case Target.Group group -> Optional.of(group.id());
            case Target.Host _, Target.ClientScript _ -> Optional.empty();
        };
    }

    private List<Target.ClientScript> clientScriptTargets(String script, String accountUuid) {
        List<Target.ClientScript> found = new ArrayList<>();
        for (Target target : targets.targetsOf(script)) {
            switch (target) {
                case Target.ClientScript cs when cs.accountUuid().equals(accountUuid) -> found.add(cs);
                case Target.ClientScript _, Target.Group _, Target.Host _ -> { }
            }
        }
        return found;
    }

    private Map<String, String> ownValues(String script, Target target) {
        return switch (target) {
            case Target.Host _ -> Map.of();
            case Target.Group _, Target.ClientScript _ -> store.overrides(script, target.key());
        };
    }
}
