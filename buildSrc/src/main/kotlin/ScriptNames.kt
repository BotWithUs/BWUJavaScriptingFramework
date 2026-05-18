/**
 * Sanitized identifiers derived from a raw user-provided script name.
 *
 * Accepts kebab-case (`woodcutting-fletcher`), snake_case
 * (`woodcutting_fletcher`), PascalCase (`WoodcuttingFletcher`), or
 * camelCase (`woodcuttingFletcher`) and produces a consistent quintuple:
 *
 *  - [moduleDirName] = lowercased kebab-case (`woodcutting-fletcher`) —
 *    the on-disk directory name and Gradle subproject path
 *  - [moduleName] = `com.botwithus.bot.scripts.<simplename>` — the
 *    Java Platform Module name, must contain no hyphens
 *  - [packageName] = `com.botwithus.bot.scripts.<simplename>` —
 *    the Java package the generated class lives in (same shape as
 *    [moduleName] by design — single-package modules)
 *  - [className] = PascalCase + `Script` suffix (`WoodcuttingFletcherScript`)
 *  - [manifestName] = human-friendly display name with spaces between words
 *    (`Woodcutting Fletcher`), used in `@ScriptManifest(name = ...)`
 */
data class ScriptNames(
        val moduleDirName: String,
        val moduleName: String,
        val packageName: String,
        val className: String,
        val manifestName: String) {

    companion object {

        fun of(rawName: String): ScriptNames {
            val trimmed = rawName.trim()
            require(trimmed.isNotEmpty()) { "scriptName must not be empty" }
            require(trimmed.matches(VALID_NAME_PATTERN)) {
                "scriptName must contain only letters, digits, hyphens, and underscores: '$trimmed'"
            }
            val words = splitToWords(trimmed)
            require(words.isNotEmpty()) { "scriptName produced no usable words: '$trimmed'" }

            val moduleDirName = words.joinToString("-") { it.lowercase() }
            val simpleName = words.joinToString("") { it.lowercase() }
            val packageName = "$BASE_PACKAGE.$simpleName"
            val pascal = words.joinToString("") { word ->
                word[0].uppercase() + word.substring(1).lowercase()
            }
            val className = "${pascal}Script"
            val manifestName = words.joinToString(" ") { word ->
                word[0].uppercase() + word.substring(1).lowercase()
            }

            return ScriptNames(
                    moduleDirName = moduleDirName,
                    moduleName = packageName,
                    packageName = packageName,
                    className = className,
                    manifestName = manifestName)
        }

        private const val BASE_PACKAGE = "com.botwithus.bot.scripts"
        private val VALID_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_-]*")
        private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

        private fun splitToWords(input: String): List<String> {
            val onSeparators = input.split('-', '_')
            return onSeparators
                    .flatMap { it.split(CAMEL_BOUNDARY) }
                    .filter { it.isNotEmpty() }
        }
    }
}
