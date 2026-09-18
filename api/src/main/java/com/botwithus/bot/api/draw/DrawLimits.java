package com.botwithus.bot.api.draw;

/**
 * The producer's hard limits on debug drawing, named once so no caller has to
 * spell a bare number.
 *
 * <p><b>Wire lockstep.</b> Every constant here mirrors one in
 * {@code NXTLibrary/src/overlay/DrawTypes.h} (or {@code kDrawBatchMax} in
 * {@code src/rpc/Handlers.cpp}). Changing a cap on the producer means changing
 * it here in the same logical change; these are the producer's numbers, not
 * this host's policy.</p>
 *
 * <p>Every cap a single call can hit is an explicit producer-side error rather
 * than a silent truncation — with one exception, documented on
 * {@link #RESOLVED_COMPONENTS_PER_TICK}.</p>
 */
public final class DrawLimits {

    /**
     * Longest key the producer stores, in <b>UTF-8 bytes</b> — its buffer is 48
     * bytes including the NUL.
     *
     * <p>Two different counters are called {@code dropped} and an over-long key
     * lands in one but not the other, so be specific about which you are reading:
     * the producer rejects the key before its store is touched, so it never
     * reaches {@link DrawStats#dropped()} (the store's process-lifetime counter),
     * but inside a batch it does count into {@link DrawBatchResult#dropped()},
     * because the batch tallies every non-OK item whatever the reason.</p>
     */
    public static final int MAX_KEY_BYTES = 47;

    /**
     * Longest text a {@code text} command may carry, in <b>UTF-16 code units</b>
     * — the producer converts the UTF-8 payload into a 128-unit wide buffer and
     * fails the command rather than truncating when it will not fit. This counts
     * Java {@code String.length()}, not UTF-8 bytes, and the two differ for any
     * non-ASCII text.
     */
    public static final int MAX_TEXT_LENGTH = 127;

    /** Most items one {@code debug_draw_set_batch} accepts. Over this is a hard error. */
    public static final int MAX_BATCH_ITEMS = 256;

    /** Retained commands per producer, across every connected client. */
    public static final int MAX_COMMANDS = 512;

    /** Retained {@code text} commands, across every connected client. */
    public static final int MAX_TEXT_COMMANDS = 64;

    /** Retained {@code poly} commands, across every connected client. */
    public static final int MAX_POLY_COMMANDS = 64;

    /**
     * Most decimal places a fixed-point caption may be scaled by. The producer
     * rejects more with {@code "decimals" must be 0..9}, and rejects {@code decimals}
     * with no {@code value} at all.
     */
    public static final int MAX_DECIMALS = 9;

    /** Fewest points a polyline may have. */
    public static final int MIN_POLY_POINTS = 2;

    /** Most points a polyline may have. */
    public static final int MAX_POLY_POINTS = 32;

    /** Thinnest stroke. */
    public static final int MIN_THICKNESS = 1;

    /** Thickest stroke. */
    public static final int MAX_THICKNESS = 64;

    /**
     * Lowest paint order.
     *
     * <p>Unlike every other limit here, this one is <b>not</b> a producer-side
     * error — and that is exactly why the host checks it. The producer stores
     * {@code z} in an {@code int16_t} and narrows to it with a plain cast, so an
     * out-of-range order is silently truncated rather than refused:
     * {@code z(100000)} arrives as {@code -31072} and quietly paints behind
     * everything instead of in front of it, with nothing in the reply, nothing in
     * {@link DrawStats#dropped()}, and nothing in {@link Draw#list()} to show for
     * it. There is no producer error for this check to agree with; the check is
     * the only thing between a caller and a silent reorder.</p>
     */
    public static final int MIN_Z = Short.MIN_VALUE;

    /** Highest paint order. See {@link #MIN_Z} for why this is enforced host-side. */
    public static final int MAX_Z = Short.MAX_VALUE;

    /**
     * Largest absolute value a <b>screen</b> coordinate may take. Out of range is
     * an error, not a clamp.
     *
     * <p>This is also the bound a world command's <i>projected</i> coordinates must
     * land inside, which the producer checks a tick later — so a world command can
     * be accepted and still fail to draw, if what it projects to is off this scale.
     * A point behind the camera projects to a saturated sentinel rather than being
     * clamped, so you learn the point is not on screen instead of getting a line to
     * nowhere.</p>
     */
    public static final int MAX_SCREEN_COORDINATE = 1 << 20;

    /**
     * Largest absolute value a <b>world</b> coordinate may take, in wire sub-tiles.
     *
     * <p>Deliberately larger than {@link #MAX_SCREEN_COORDINATE}, because the two
     * gate different quantities: this one bounds what a caller <i>sends</i>, while
     * the screen bound applies to what those coordinates <i>project to</i>. Reusing
     * one number for both would have refused legal world positions — anything past
     * tile 4096 — for failing a limit that is about pixels.</p>
     *
     * <p><b>Where the number comes from, because it is not round.</b> Every absolute
     * world tile the producer publishes in its snapshot — NPC, player, location,
     * ground item, projectile — is an {@code int16_t}. So the widest tile magnitude
     * any consumer can be holding is {@code |Short.MIN_VALUE|} = 32768, and at
     * {@link #SUBTILE_SCALE} sub-tiles per tile that is 2^23. A bound below this
     * would refuse a coordinate the same producer published a tick earlier.</p>
     *
     * <p>The practical consequence: a tile taken from a snapshot can never exceed
     * this, because the snapshot cannot express one that does. Underground content,
     * which sits at {@code y + 6400}, is nowhere near it.</p>
     */
    public static final int MAX_WORLD_COORDINATE = 1 << 23;

    /** Largest width or height a rect or ellipse may have. */
    public static final int MAX_EXTENT = 1 << 14;

    /**
     * The TTL the producer applies when a command omits {@code ttl_ms}. This API
     * never omits it — {@link DrawStyle} always puts a TTL on the wire so the
     * lifetime a command actually got is visible in {@link Draw#list()} rather
     * than being an invisible producer-side default.
     */
    public static final long DEFAULT_TTL_MS = 3000L;

    /**
     * TTL meaning "until replaced, cleared, or my connection closes". It is not
     * a way to draw something that outlives the script: ownership is
     * connection-scoped and the producer drops everything a connection drew when
     * its pipe closes.
     */
    public static final long TTL_UNTIL_CLEARED = 0L;

    /**
     * Component highlights the producer re-resolves per tick. This is the one
     * cap that is <b>not</b> an error, because it is not reachable from a single
     * call: past it the surplus resolves on a following tick and collection
     * rotates, so nothing is starved permanently — but geometry beyond this many
     * live highlights lags the game by a tick or more, and
     * {@link DrawStats#resolveOverflow()} counts the ticks on which that happened.
     */
    public static final int RESOLVED_COMPONENTS_PER_TICK = 64;

    /** Fixed-point sub-tile divisions per tile, for {@link DrawSpace#WORLD} geometry. */
    public static final int SUBTILE_SCALE = 256;

    private DrawLimits() {
    }
}
