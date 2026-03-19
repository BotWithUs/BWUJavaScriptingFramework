package com.botwithus.bot.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for {@link BotScript} implementations to declare script metadata.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @ScriptManifest(name = "Woodcutter", version = "1.2", author = "Dev", description = "Chops trees")
 * public class WoodcutterScript implements BotScript { ... }
 * }</pre>
 *
 * @see BotScript
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScriptManifest {

    /**
     * The display name of the script.
     *
     * @return the script name
     */
    String name();

    /**
     * The version string of the script.
     *
     * @return the version, defaults to {@code "1.0"}
     */
    String version() default "1.0";

    /**
     * The author of the script.
     *
     * @return the author name, defaults to empty
     */
    String author() default "";

    /**
     * A brief description of what the script does.
     *
     * @return the description, defaults to empty
     */
    String description() default "";

    /**
     * The category this script belongs to.
     *
     * @return the script category, defaults to {@link ScriptCategory#UNCATEGORIZED}
     */
    ScriptCategory category() default ScriptCategory.UNCATEGORIZED;
}
