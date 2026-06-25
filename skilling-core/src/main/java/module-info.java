/**
 * skilling-core — the shared skilling SDK and the Atlas data-layer reader.
 *
 * <p>Skill scripts (woodcutting-script, future mining/smithing) declare
 * {@code requires com.botwithus.bot.skilling} and extend the
 * {@link com.botwithus.bot.skilling.script.SkillScript} /
 * {@link com.botwithus.bot.skilling.script.GatherScript} bases. The Atlas
 * ({@link com.botwithus.bot.skilling.atlas.Atlas}) reads the pre-baked
 * {@code resolved.sqlite} via sqlite-jdbc; recipe blobs are parsed with gson's
 * tree API (no reflective binding, so no {@code opens} is needed).</p>
 */
module com.botwithus.bot.skilling {
    // transitive: the SDK base classes and Atlas records appear in signatures
    // scripts compile against, and scripts get api (+ slf4j) through us.
    requires transitive com.botwithus.bot.api;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires com.google.gson;

    exports com.botwithus.bot.skilling.atlas;
    exports com.botwithus.bot.skilling.script;
    exports com.botwithus.bot.skilling.banking;
    exports com.botwithus.bot.skilling.inventory;
    exports com.botwithus.bot.skilling.plan;
}
