package com.botwithus.bot.core.alerts;

import java.io.IOException;

/**
 * Makes one HTTP POST, with no retries. The seam between the notifiers and the
 * network: {@link JdkHttpTransport} in the host, a scripted fake in tests.
 */
@FunctionalInterface
public interface HttpTransport {

    /**
     * Posts {@code request} and returns the reply, whatever its status.
     *
     * @throws java.net.http.HttpTimeoutException if the server did not answer in time
     * @throws IOException                        if the request could not be made
     * @throws InterruptedException               if the calling thread was interrupted
     */
    HttpReply post(HttpPost request) throws IOException, InterruptedException;
}
