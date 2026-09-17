import org.gradle.api.Project
import java.util.Properties

/**
 * Resolves a build-time configuration value that may carry a machine-specific
 * path, checking these sources in order (highest precedence first):
 *
 *  1. a Gradle project property — `-Pkey=...`, the project `gradle.properties`,
 *     or `~/.gradle/gradle.properties`;
 *  2. `local.properties` at the root project directory — git-ignored and
 *     per-developer (see `local.properties.example`);
 *  3. an environment variable, when [envVar] is supplied;
 *  4. `null` when nothing provides the key.
 *
 * `local.properties` is the recommended home for paths that differ between
 * machines (where `NXTCache.dll` / `worldwalker.dll` were built, a jlink JDK
 * home, …) so none of those paths get committed. Use forward slashes — a
 * backslash starts an escape sequence in a `.properties` file, so `C:\foo`
 * is silently read as `C:foo`.
 */
fun Project.localProperty(key: String, envVar: String? = null): String? {
    (findProperty(key) as String?)?.let { return it }

    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) {
        val props = Properties()
        localFile.inputStream().use { props.load(it) }
        props.getProperty(key)?.let { return it }
    }

    return envVar?.let { System.getenv(it) }
}
