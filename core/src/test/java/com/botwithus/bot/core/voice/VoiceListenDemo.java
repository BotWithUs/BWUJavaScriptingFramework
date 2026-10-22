package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceSession;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manual check on a real microphone: listens for "hello bot" and "stop" on the Windows
 * default recording device and prints each phrase heard, until "stop" is heard or the time
 * runs out.
 *
 * <pre>{@code ./gradlew :core:voiceDemo}</pre>
 *
 * <p>Pass a number of seconds to listen for a different time:
 * {@code ./gradlew :core:voiceDemo --args="120"}.</p>
 */
public final class VoiceListenDemo {

    private static final String STOP = "stop";
    private static final List<String> PHRASES = List.of("hello bot", STOP);
    private static final long DEFAULT_SECONDS = 60;

    private VoiceListenDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_SECONDS;
        try (VoiceInputService voice = VoiceInputService.windowsDefaultMicrophone()) {
            boolean available = voice.available();
            System.out.println("Speech recognition available: " + available);
            if (!available) {
                System.out.println("No recogniser, language pack or microphone; nothing to do.");
                return;
            }
            BlockingQueue<Heard> heard = new LinkedBlockingQueue<>();
            try (VoiceSession _ = voice.listen(PHRASES, h -> {
                System.out.printf("heard \"%s\" (confidence %.2f) on %s%n",
                        h.phrase(), h.confidence(), Thread.currentThread().getName());
                heard.add(h);
            })) {
                System.out.println("Listening for " + PHRASES + " for " + seconds + " s. Say \"stop\" to end.");
                listenUntilStop(heard, System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
            }
        }
        System.out.println("Done.");
    }

    private static void listenUntilStop(BlockingQueue<Heard> heard, long deadline) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            Heard next = heard.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            if (next.phrase().equals(STOP)) {
                return;
            }
        }
    }
}
