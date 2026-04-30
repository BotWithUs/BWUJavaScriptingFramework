package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.event.*;
import com.botwithus.bot.api.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Lock-free single-consumer view of the producer's event ring. The producer
 * (NXTLibrary's main-thread tick detour) claims slots via
 * {@code InterlockedIncrement64} on {@code head} and commits via
 * {@code InterlockedExchange64} on {@code slot.seq}. We pull-poll: track
 * the next-expected sequence number, race the writer at the slot-seq
 * boundary, and bail without consuming if we caught it mid-write.
 *
 * <p>Drop detection: when our expected seq is behind the slot's committed
 * seq, the writer wrapped past us. We skip ahead, count the missed events,
 * and surface the count via {@link #droppedCount()} for diagnostics. The
 * underlying ring also maintains a coarse {@code droppedCount} field the
 * writer increments — accessible via {@link #writerSideDroppedCount()}.</p>
 *
 * <p>Each {@link #poll} call drains everything currently visible at
 * {@code head} and returns a count. Typical use: schedule on a 10–50 ms
 * poll loop or piggy-back on the bot's main tick. At 60 Hz publish and
 * 1024 slots, a reader has ~17 s of slack before the writer can wrap.</p>
 */
public final class EventRingReader {

    private static final Logger log = LoggerFactory.getLogger(EventRingReader.class);

    private final MemorySegment ring;
    private final int slotMask;

    private long nextSeq;         // next sequence we expect to see
    private long droppedCount;    // sum over the lifetime of this reader

    /**
     * @param region the open shared region. Reader is bound to its
     *               underlying ring segment — the segment's lifetime must
     *               outlive every {@link #poll} call.
     * @param fromHead if true, start consuming from the current head (skip
     *                 backlog); if false, replay everything still in the
     *                 ring (oldest visible event onward). Production code
     *                 should pass {@code true} — the backlog is meaningless
     *                 when the reader attaches mid-session.
     */
    public EventRingReader(SharedRegion region, boolean fromHead) {
        this.ring = region.ring();
        int sm = ring.get(ValueLayout.JAVA_INT, Layout.RING_SLOTMASK_OFFSET);
        this.slotMask = sm;
        long head = ring.get(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET);
        if (fromHead) {
            this.nextSeq = head;
        } else {
            // The writer wraps every slotCount events; the oldest still-
            // readable event is at head - slotCount (clamped to 0).
            int slotCount = sm + 1;
            this.nextSeq = Math.max(0L, head - slotCount);
        }
    }

    /** Reads {@code droppedCount} from the ring header (writer-side counter). */
    public int writerSideDroppedCount() {
        return ring.get(ValueLayout.JAVA_INT, Layout.RING_DROPPEDCOUNT_OFFSET);
    }

    /** Total events this reader skipped due to writer-side overrun. */
    public long droppedCount() { return droppedCount; }

    /** Next sequence we'll attempt to read; advances each successful poll. */
    public long nextSeq() { return nextSeq; }

    /**
     * Pumps everything visible at {@code head}, decoding each event and
     * delivering it to {@code consumer}. Returns the number of events
     * delivered. Unknown event types are skipped silently (forward-compat
     * with future producer versions).
     */
    public int poll(Consumer<GameEvent> consumer) {
        // Acquire-load via volatile read of head — Panama JAVA_LONG load on
        // an aligned 8-byte address is sequentially consistent on x64.
        long head = ring.get(ValueLayout.JAVA_LONG, Layout.RING_HEAD_OFFSET);

        int delivered = 0;
        long seq = nextSeq;
        while (seq < head) {
            long slotOffset = Layout.RING_SLOTS_OFFSET
                    + ((seq & slotMask) * (long) Layout.EVENT_SLOT_SIZE);
            long slotSeq = ring.get(ValueLayout.JAVA_LONG, slotOffset + Layout.SLOT_SEQ_OFFSET);
            if (slotSeq == seq) {
                GameEvent ev = decodeSlot(slotOffset);
                if (ev != null) {
                    try {
                        consumer.accept(ev);
                        ++delivered;
                    } catch (RuntimeException re) {
                        // A buggy subscriber must not stall the consumer.
                        // Log and continue draining.
                        log.warn("event consumer threw; continuing drain", re);
                    }
                }
                ++seq;
            } else if (Long.compareUnsigned(slotSeq, seq) > 0) {
                // Writer wrapped past us. Skip to the slot's current seq
                // and keep going — anything strictly between [seq, slotSeq)
                // is permanently lost.
                long missed = slotSeq - seq;
                droppedCount += missed;
                log.warn("event ring overrun: missed {} events (lifetime drops: {})",
                        missed, droppedCount);
                seq = slotSeq;
            } else {
                // slotSeq < seq: producer claimed this slot but hasn't
                // committed yet. Bail and retry next poll — the seq we
                // wanted will eventually appear or be overwritten.
                break;
            }
        }
        nextSeq = seq;
        return delivered;
    }

    // ------------------------------------------------------------------
    // Body decoders. All offsets are relative to the slot start.
    // ------------------------------------------------------------------

    private GameEvent decodeSlot(long slotOffset) {
        int type    = ring.get(ValueLayout.JAVA_INT, slotOffset + Layout.SLOT_TYPE_OFFSET);
        int bodyLen = ring.get(ValueLayout.JAVA_INT, slotOffset + Layout.SLOT_BODYLEN_OFFSET);
        long bodyOff = slotOffset + Layout.SLOT_BODY_OFFSET;

        return switch (type) {
            case Layout.EVT_LOGIN_STATE_CHANGE -> decodeLoginStateChange(bodyOff, bodyLen);
            case Layout.EVT_TICK               -> decodeTick(bodyOff, bodyLen);
            case Layout.EVT_VAR_CHANGE         -> decodeVarChange(bodyOff, bodyLen);
            case Layout.EVT_VARBIT_CHANGE      -> decodeVarbitChange(bodyOff, bodyLen);
            case Layout.EVT_VARC_CHANGE        -> null;     // no Java event class yet
            case Layout.EVT_CHAT_MESSAGE       -> decodeChatMessage(bodyOff, bodyLen);
            case Layout.EVT_KEY_INPUT          -> decodeKeyInput(bodyOff, bodyLen);
            case Layout.EVT_ACTION_EXECUTED    -> decodeActionExecuted(bodyOff, bodyLen);
            case Layout.EVT_BREAK_STARTED      -> decodeBreakStarted(bodyOff, bodyLen);
            case Layout.EVT_BREAK_ENDED        -> new BreakEndedEvent();
            case Layout.EVT_WALK_ARRIVED       -> decodeWalk(bodyOff, bodyLen, /*outcome*/0);
            case Layout.EVT_WALK_CANCELLED     -> decodeWalk(bodyOff, bodyLen, /*outcome*/1);
            case Layout.EVT_WALK_FAILED        -> decodeWalk(bodyOff, bodyLen, /*outcome*/2);
            default -> null;       // forward-compat: skip unknowns
        };
    }

    private LoginStateChangeEvent decodeLoginStateChange(long off, int len) {
        if (len < 8) return null;
        int oldState = ring.get(ValueLayout.JAVA_INT, off);
        int newState = ring.get(ValueLayout.JAVA_INT, off + 4);
        return new LoginStateChangeEvent(oldState, newState);
    }

    private TickEvent decodeTick(long off, int len) {
        if (len < 4) return null;
        int tick = ring.get(ValueLayout.JAVA_INT, off);
        return new TickEvent(tick);
    }

    private VarChangeEvent decodeVarChange(long off, int len) {
        if (len < 12) return null;
        int id    = ring.get(ValueLayout.JAVA_INT, off);
        int oldV  = ring.get(ValueLayout.JAVA_INT, off + 4);
        int newV  = ring.get(ValueLayout.JAVA_INT, off + 8);
        return new VarChangeEvent(id, oldV, newV);
    }

    private VarbitChangeEvent decodeVarbitChange(long off, int len) {
        if (len < 12) return null;
        int id    = ring.get(ValueLayout.JAVA_INT, off);
        int oldV  = ring.get(ValueLayout.JAVA_INT, off + 4);
        int newV  = ring.get(ValueLayout.JAVA_INT, off + 8);
        return new VarbitChangeEvent(id, oldV, newV);
    }

    private ChatMessageEvent decodeChatMessage(long off, int len) {
        // Body layout: i32 msgType, u16 senderLen, u16 textLen, u8 buf[..].
        if (len < 8) return null;
        int msgType   = ring.get(ValueLayout.JAVA_INT,   off);
        int senderLen = ring.get(ValueLayout.JAVA_SHORT, off + 4) & 0xFFFF;
        int textLen   = ring.get(ValueLayout.JAVA_SHORT, off + 6) & 0xFFFF;
        long bufOff   = off + 8;
        int bufCap    = Layout.EVENT_BODY_MAX - 8;
        if (senderLen > bufCap) senderLen = bufCap;
        if (textLen   > bufCap - senderLen) textLen = bufCap - senderLen;

        String sender = readUtf8(bufOff, senderLen);
        String text   = readUtf8(bufOff + senderLen, textLen);
        // ChatMessage.index is wire-absent for now; pass 0. Player name is
        // null if the producer left the sender field empty (system msgs).
        return new ChatMessageEvent(new ChatMessage(
                0, msgType, text, sender.isEmpty() ? null : sender));
    }

    private KeyInputEvent decodeKeyInput(long off, int len) {
        if (len < 8) return null;
        int key   = ring.get(ValueLayout.JAVA_INT,  off);
        boolean isAlt   = ring.get(ValueLayout.JAVA_BYTE, off + 4) != 0;
        boolean isCtrl  = ring.get(ValueLayout.JAVA_BYTE, off + 5) != 0;
        boolean isShift = ring.get(ValueLayout.JAVA_BYTE, off + 6) != 0;
        return new KeyInputEvent(key, isAlt, isCtrl, isShift);
    }

    private ActionExecutedEvent decodeActionExecuted(long off, int len) {
        if (len < 16) return null;
        int actionId = ring.get(ValueLayout.JAVA_INT, off);
        int p1       = ring.get(ValueLayout.JAVA_INT, off + 4);
        int p2       = ring.get(ValueLayout.JAVA_INT, off + 8);
        int p3       = ring.get(ValueLayout.JAVA_INT, off + 12);
        return new ActionExecutedEvent(actionId, p1, p2, p3);
    }

    private BreakStartedEvent decodeBreakStarted(long off, int len) {
        // Layout: i32 duration, u32 _pad, f64 fatigue, f64 risk.
        if (len < 24) return null;
        int duration  = ring.get(ValueLayout.JAVA_INT,    off);
        double fatigue = ring.get(ValueLayout.JAVA_DOUBLE, off + 8);
        double risk    = ring.get(ValueLayout.JAVA_DOUBLE, off + 16);
        return new BreakStartedEvent(duration, fatigue, risk);
    }

    private GameEvent decodeWalk(long off, int len, int outcome) {
        if (len < 8) return null;
        int targetX = ring.get(ValueLayout.JAVA_INT, off);
        int targetY = ring.get(ValueLayout.JAVA_INT, off + 4);
        return switch (outcome) {
            case 0 -> new WalkArrivedEvent(targetX, targetY);
            case 1 -> new WalkCancelledEvent(targetX, targetY);
            case 2 -> new WalkFailedEvent(targetX, targetY);
            default -> null;
        };
    }

    private String readUtf8(long off, int len) {
        if (len <= 0) return "";
        byte[] tmp = new byte[len];
        MemorySegment.copy(ring, ValueLayout.JAVA_BYTE, off, tmp, 0, len);
        return new String(tmp, StandardCharsets.UTF_8);
    }
}
