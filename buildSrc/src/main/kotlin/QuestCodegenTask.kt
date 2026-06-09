import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates `Quests.java` — a thin registry of [com.botwithus.bot.quest.QuestId]
 * constants — from `merged_quests.json` and `action_chains_sample.json` in a
 * user-supplied research directory.
 *
 * Why thin: the cache-resident quest definition (skill reqs, dependent quests,
 * start tile) resolves at runtime through `GameAPI.getQuestType(id)`. Baking it
 * in here would just create a cache-drift bug. The codegen freezes only the
 * tuple needed *before* any RPC: id, name, and the tracker var ids the
 * progress tracker needs to subscribe to.
 *
 * Tracker source precedence:
 *   1. `action_chains_sample.json::chains[].tracker.id` (authored mapping)
 *   2. first id of each entry in `cache.progressVarbits` then `cache.progressVarps`
 *      (each entry is `[varId, minValue, maxValue]`)
 *
 * Codegen is opt-in. When [researchDir] is unset or `merged_quests.json` is
 * missing, the task emits a stub `Quests.java` with no constants so the
 * `quest-core` module still compiles in a fresh checkout.
 */
abstract class QuestCodegenTask : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val researchDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "build"
        description = "Generates Quests.java from the quest research directory."
    }

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val outRoot = outputDir.get().asFile
        outRoot.deleteRecursively()
        val pkgDir = File(outRoot, pkg.replace('.', File.separatorChar))
        pkgDir.mkdirs()
        val out = File(pkgDir, "Quests.java")

        val dir = researchDir.orNull?.asFile
        if (dir == null || !dir.isDirectory) {
            out.writeText(stub(pkg, "research dir not configured"))
            return
        }
        val mergedFile = File(dir, "merged_quests.json")
        if (!mergedFile.isFile) {
            out.writeText(stub(pkg, "merged_quests.json missing"))
            return
        }

        val trackerOverrides = readTrackerOverrides(File(dir, "action_chains_sample.json"))
        val quests = readQuests(mergedFile)

        val constants = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (q in quests) {
            val qid = (q["quest_id"] as? Number)?.toInt() ?: continue
            val title = (q["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) continue

            val trackers = resolveTrackerVars(qid, q, trackerOverrides)
            if (trackers.isEmpty()) continue

            var name = constName(title)
            if (!seen.add(name)) {
                name = "${name}_$qid"
                seen.add(name)
            }
            constants += "    public static final QuestId $name = " +
                    "new QuestId($qid, \"${escape(title)}\", new int[]{ ${trackers.joinToString(", ")} });"
        }

        out.writeText(emit(pkg, constants))
        logger.lifecycle("Generated {} QuestId constants → {}", constants.size, out)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTrackerOverrides(file: File): Map<Int, Int> {
        if (!file.isFile) return emptyMap()
        val root = JsonSlurper().parse(file.reader()) as Map<String, Any?>
        val chains = root["chains"] as? List<Map<String, Any?>> ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (c in chains) {
            val qid = (c["quest_id"] as? Number)?.toInt() ?: continue
            val tracker = c["tracker"] as? Map<String, Any?> ?: continue
            val tid = (tracker["id"] as? Number)?.toInt() ?: continue
            out[qid] = tid
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun readQuests(file: File): List<Map<String, Any?>> {
        val root = JsonSlurper().parse(file.reader()) as Map<String, Any?>
        return root["quests"] as? List<Map<String, Any?>> ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveTrackerVars(
        qid: Int,
        quest: Map<String, Any?>,
        overrides: Map<Int, Int>,
    ): List<Int> {
        overrides[qid]?.let { return listOf(it) }
        val cache = quest["cache"] as? Map<String, Any?> ?: return emptyList()
        val varbits = cache["progressVarbits"] as? List<List<Any?>> ?: emptyList()
        val varps = cache["progressVarps"] as? List<List<Any?>> ?: emptyList()
        val ids = mutableListOf<Int>()
        for (entry in varbits + varps) {
            val first = (entry.firstOrNull() as? Number)?.toInt() ?: continue
            ids += first
        }
        return ids
    }

    private fun constName(title: String): String {
        val collapsed = title.replace("'", "")
        val sb = StringBuilder()
        var underscore = true
        for (ch in collapsed) {
            if (ch.code in 0x30..0x39 || ch.code in 0x41..0x5A || ch.code in 0x61..0x7A) {
                sb.append(ch.uppercaseChar())
                underscore = false
            } else if (!underscore) {
                sb.append('_')
                underscore = true
            }
        }
        val trimmed = sb.toString().trim('_').ifEmpty { "QUEST" }
        return if (trimmed[0].isDigit()) "Q_$trimmed" else trimmed
    }

    private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun emit(pkg: String, constants: List<String>): String = buildString {
        appendLine("// Generated by QuestCodegenTask — do not hand-edit.")
        appendLine("package $pkg;")
        appendLine()
        appendLine("/**")
        appendLine(" * Generated registry of {@link QuestId} constants — one per quest declared")
        appendLine(" * in {@code merged_quests.json}. Skill / dependent-quest / start-tile data")
        appendLine(" * is intentionally NOT baked in; resolve at runtime through")
        appendLine(" * {@link com.botwithus.bot.api.GameAPI#getQuestType(int)} so it tracks")
        appendLine(" * cache changes.")
        appendLine(" */")
        appendLine("public final class Quests {")
        appendLine()
        appendLine("    private Quests() {}")
        if (constants.isNotEmpty()) appendLine()
        for (c in constants) appendLine(c)
        appendLine("}")
    }

    private fun stub(pkg: String, reason: String): String = buildString {
        appendLine("// Generated by QuestCodegenTask — codegen skipped: $reason")
        appendLine("package $pkg;")
        appendLine()
        appendLine("/** Stub registry — set `quest.research.dir` in local.properties to populate. */")
        appendLine("public final class Quests {")
        appendLine("    private Quests() {}")
        appendLine("}")
    }
}
