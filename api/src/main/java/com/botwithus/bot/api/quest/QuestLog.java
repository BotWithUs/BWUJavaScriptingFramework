package com.botwithus.bot.api.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.QuestVar;
import com.botwithus.bot.api.model.SkillRequirement;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Status of any quest for the logged-in player, from the quest's cache definition
 * ({@link GameAPI#getQuestType}) and the player's variables. Obtained from
 * {@link GameAPI#quests()}.
 *
 * <p>A quest's definition names the variable its progress is kept in, with a start and an end
 * value ({@link QuestType#progressVars()}). The quest is started once the variable reaches
 * the start value and complete once it reaches the end value. Like v1, only the first variable
 * listed is read; a definition lists varps before varbits.</p>
 *
 * <p>Started means <em>at or above</em> the start value, not above it as v1 had it. Cook's
 * Assistant's definition is start 1, end 2 (read live on 951-4): with v1's rule no value of
 * its progress variable would read "started".</p>
 *
 * <p><b>Relation to quest-core.</b> {@code QuestProgressTracker} follows the variables one
 * scripted quest lists, event by event, so a quest script can branch on the exact step it is
 * at. This class answers the coarser question for any quest id, with one read per call and no
 * subscription. quest-core's {@code QuestRequirements} asks this class which prior quests are
 * incomplete.</p>
 *
 * <p><b>Stub records.</b> The cache holds some quests twice: a stub with an empty
 * {@code listName} and no progress variable (gameval {@code COOK_QUEST}, id 6) beside the
 * real, listed quest of the same name ({@code COOKS_ASSISTANT}, 257). Asked about a stub, this
 * class reads the listed quest of the same name instead, and {@link QuestStatus#questId()}
 * names the record it read. The name index behind that is built once per instance, from the
 * gameval index, the first time a stub is asked about.</p>
 *
 * <p>Quest points are not checked: the game holds the player's total in a variable that has
 * not been identified, so a quest-point requirement is not reported either way.</p>
 */
public final class QuestLog {

    private static final Logger log = LoggerFactory.getLogger(QuestLog.class);

    /** Upper bound on quest gamevals read when building {@link #listedByName()}. */
    private static final int MAX_QUESTS = 4096;

    private final GameAPI api;
    /** Built by {@link #listedByName()} on first use; replaced whole, never mutated. */
    private volatile Map<String, Integer> listedIndex;

    public QuestLog(GameAPI api) {
        this.api = api;
    }

    /**
     * The quest's status. Never null: anything that cannot be read is
     * {@link QuestStage#UNKNOWN}. One definition lookup and one variable read.
     */
    public QuestStatus status(int questId) {
        Optional<QuestType> type = questType(questId).map(this::preferListed);
        if (type.isEmpty()) {
            return QuestStatus.unknown(questId, "");
        }
        questId = type.get().id();
        List<QuestVar> vars = type.get().progressVars();
        if (vars.isEmpty()) {
            return QuestStatus.unknown(questId, type.get().name());
        }
        QuestVar variable = vars.getFirst();
        OptionalInt value = read(variable);
        if (value.isEmpty()) {
            return QuestStatus.unknown(questId, type.get().name());
        }
        return new QuestStatus(questId, type.get().name(), stageOf(variable, value.getAsInt()),
                value.getAsInt(), Optional.of(variable));
    }

    /**
     * The status of the quest the gameval index names {@code gameval}, such as
     * {@code "COOKS_ASSISTANT"}.
     *
     * @return the status, or empty when the name does not resolve
     */
    public Optional<QuestStatus> status(String gameval) {
        return idOf(gameval).map(this::status);
    }

    /** True once the quest is started or complete; false when unknown. */
    public boolean isStarted(int questId) {
        return status(questId).isStarted();
    }

    /** True when the quest is complete; false when unknown. */
    public boolean isComplete(int questId) {
        return status(questId).isComplete();
    }

    /** The raw progress value, or {@link QuestStatus#UNKNOWN_PROGRESS} when it cannot be read. */
    public int progress(int questId) {
        return status(questId).progress();
    }

    /**
     * Every requirement for starting the quest the player does not meet: skill levels,
     * membership and prior quests. Empty when all are met, and also when the quest has no
     * definition to read requirements from. Quest points are not checked (see the class).
     */
    public List<QuestRequirement> unmetRequirements(int questId) {
        Optional<QuestType> type = questType(questId).map(this::preferListed);
        if (type.isEmpty()) {
            return List.of();
        }
        List<QuestRequirement> unmet = new ArrayList<>();
        checkMembership(type.get(), unmet);
        checkSkills(type.get(), unmet);
        for (int prior : type.get().dependentQuestIds()) {
            if (!isComplete(prior)) {
                unmet.add(new QuestRequirement.PriorQuest(prior));
            }
        }
        return List.copyOf(unmet);
    }

    /** True when {@link #unmetRequirements(int)} is empty. */
    public boolean requirementsMet(int questId) {
        return unmetRequirements(questId).isEmpty();
    }

    static QuestStage stageOf(QuestVar variable, int value) {
        if (value >= variable.endValue()) {
            return QuestStage.COMPLETE;
        }
        return value >= variable.startValue() ? QuestStage.STARTED : QuestStage.NOT_STARTED;
    }

    private OptionalInt read(QuestVar variable) {
        if (variable.kind() == QuestVar.Kind.VARP) {
            VarpRead varp = api.readVarp(variable.id());
            return varp != null && varp.hasValue() ? OptionalInt.of(varp.value()) : OptionalInt.empty();
        }
        VarbitRead varbit = api.readVarbit(variable.id());
        return varbit != null && varbit.hasValue() ? OptionalInt.of(varbit.value()) : OptionalInt.empty();
    }

    private void checkMembership(QuestType type, List<QuestRequirement> unmet) {
        if (!type.membersOnly()) {
            return;
        }
        GameSnapshot snapshot = api.snapshot();
        LocalPlayer self = snapshot == null ? null : snapshot.self();
        if (self != null && !self.isMember()) {
            unmet.add(new QuestRequirement.Membership());
        }
    }

    private void checkSkills(QuestType type, List<QuestRequirement> unmet) {
        for (SkillRequirement required : type.requiredSkills()) {
            PlayerStat stat = api.getPlayerStat(required.skillId());
            int actual = stat == null ? 0 : stat.actualLevel();
            if (actual < required.level()) {
                unmet.add(new QuestRequirement.Skill(required.skillId(), required.level(), actual));
            }
        }
    }

    /**
     * {@code type} itself when it is a listed quest; for an unlisted stub record (empty
     * {@code listName}), the listed quest of the same name when there is one.
     */
    private QuestType preferListed(QuestType type) {
        if (isListed(type)) {
            return type;
        }
        Integer listed = listedByName().get(type.name());
        if (listed == null || listed == type.id()) {
            return type;
        }
        return questType(listed).orElse(type);
    }

    /**
     * Name -> id of every listed quest the gameval index names, built on first need and kept:
     * definitions do not change within a client session.
     */
    private Map<String, Integer> listedByName() {
        Map<String, Integer> index = listedIndex;
        if (index != null) {
            return index;
        }
        Map<String, Integer> built = new HashMap<>();
        for (GamevalEntry entry : api.gamevals().startingWith(GamevalType.QUEST, "", MAX_QUESTS)) {
            questType(entry.id()).filter(QuestLog::isListed)
                    .ifPresent(quest -> built.putIfAbsent(quest.name(), quest.id()));
        }
        listedIndex = Map.copyOf(built);
        return listedIndex;
    }

    private static boolean isListed(QuestType type) {
        return type.listName() != null && !type.listName().isEmpty();
    }

    private Optional<QuestType> questType(int questId) {
        try {
            return Optional.ofNullable(api.getQuestType(questId));
        } catch (RuntimeException e) {
            log.debug("getQuestType({}) failed; quest status unknown", questId, e);
            return Optional.empty();
        }
    }

    private Optional<Integer> idOf(String gameval) {
        OptionalInt id = api.gamevals().id(GamevalType.QUEST, gameval);
        return id.isPresent() ? Optional.of(id.getAsInt()) : Optional.empty();
    }
}
