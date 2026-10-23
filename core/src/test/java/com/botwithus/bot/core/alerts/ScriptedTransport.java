package com.botwithus.bot.core.alerts;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An {@link HttpTransport} that answers from a script, records every request, and
 * advances a {@link ManualClock} by a fixed round-trip per request. Its matching
 * {@link #sleeper()} records each wait and advances the same clock, so a test
 * sees exactly how long a send took without anything sleeping.
 */
final class ScriptedTransport implements HttpTransport {

    /** One scripted answer: a reply, or an exception to throw. */
    @FunctionalInterface
    interface Answer {
        HttpReply answer() throws IOException, InterruptedException;
    }

    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<HttpPost> requests = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final ManualClock clock;
    private final Duration roundTrip;

    ScriptedTransport(ManualClock clock, Duration roundTrip) {
        this.clock = clock;
        this.roundTrip = roundTrip;
    }

    ScriptedTransport then(Answer answer) {
        answers.add(answer);
        return this;
    }

    ScriptedTransport thenReply(int status, String body) {
        return then(() -> new HttpReply(status, body));
    }

    @Override
    public HttpReply post(HttpPost request) throws IOException, InterruptedException {
        requests.add(request);
        clock.advance(roundTrip);
        Answer answer = answers.poll();
        if (answer == null) {
            throw new AssertionError("no scripted answer for request " + requests.size());
        }
        return answer.answer();
    }

    Sleeper sleeper() {
        return duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        };
    }

    List<HttpPost> requests() {
        return List.copyOf(requests);
    }

    HttpPost onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("expected one request, got " + requests.size());
        }
        return requests.getFirst();
    }

    List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }
}
