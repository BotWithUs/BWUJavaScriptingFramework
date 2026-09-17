package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static helper that resolves a {@link QuestId} into a {@link RequirementCheck}
 * against the live player. The cache-resident {@link QuestType} provides the
 * skill / QP / dependent-quest / membership requirements; the
 * {@link GameSnapshot} provides the live player stats and member flag.
 *
 * <p>Treat the result as advisory. {@link GameAPI#getQuestType(int)} is
 * currently a slice-5 stub on the producer, so when no real definition is
 * returned the check defaults to {@link RequirementCheck#pass()}. Skill /
 * membership checks against a real definition are exact; QP and
 * dependent-quest checks are stubbed out until those reads land (the
 * producer currently has no surface for "is quest X complete?" or
 * "what's the player's total QP?").</p>
 */
public final class QuestRequirements {

    private QuestRequirements() {}

    public static RequirementCheck check(QuestId quest, GameAPI api) {
        QuestType type = safeQuestType(api, quest.id());
        if (type == null) {
            return RequirementCheck.pass();
        }

        List<RequirementCheck.Missing> missing = new ArrayList<>();
        checkMembership(type, api, missing);
        checkSkills(type, api, missing);
        return missing.isEmpty()
                ? RequirementCheck.pass()
                : RequirementCheck.fail(missing);
    }

    private static void checkMembership(QuestType type, GameAPI api,
                                        List<RequirementCheck.Missing> out) {
        if (!type.membersOnly()) {
            return;
        }
        GameSnapshot snap = api.snapshot();
        LocalPlayer lp = snap == null ? null : snap.self();
        if (lp != null && !lp.isMember()) {
            out.add(new RequirementCheck.Missing.Membership());
        }
    }

    private static void checkSkills(QuestType type, GameAPI api,
                                    List<RequirementCheck.Missing> out) {
        for (Map<String, Object> req : type.skillRequirements()) {
            Integer skillId = asInt(req.get("skill"));
            Integer requiredLevel = asInt(req.get("level"));
            if (skillId == null || requiredLevel == null) {
                continue;
            }
            PlayerStat stat = safePlayerStat(api, skillId);
            int actual = stat == null ? 0 : stat.actualLevel();
            if (actual < requiredLevel) {
                out.add(new RequirementCheck.Missing.Skill(skillId, requiredLevel, actual));
            }
        }
    }

    private static QuestType safeQuestType(GameAPI api, int id) {
        try {
            return api.getQuestType(id);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static PlayerStat safePlayerStat(GameAPI api, int skillId) {
        try {
            return api.getPlayerStat(skillId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
