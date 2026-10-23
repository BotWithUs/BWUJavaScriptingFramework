package com.botwithus.bot.cli.clients;

import java.io.IOException;
import java.util.List;

/** Where remembered clients are kept between runs of the host. */
public interface ClientStore {

    /** The clients saved last; empty when nothing was ever saved. */
    List<RememberedClient> load() throws IOException;

    /** Replaces what is saved with {@code clients}, in order. */
    void save(List<RememberedClient> clients) throws IOException;
}
