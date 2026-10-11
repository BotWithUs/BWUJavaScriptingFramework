package com.botwithus.bot.core.crypto;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.util.Objects;

/**
 * The named module a delivered SDN jar declares, as its loader reports it.
 *
 * <p>The descriptor is read from the sealed {@code module-info.class} by the
 * runtime, so the host only ever sees names and flags. The finder finds exactly
 * this module and is meant to be the before-finder of
 * {@code Configuration.resolveAndBind}; the layer built from it must map the
 * module to the same loader that reported it, because one loader backs one
 * module.
 *
 * @param descriptor the module's descriptor
 * @param finder     a finder that finds exactly that module
 */
public record SdnModule(ModuleDescriptor descriptor, ModuleFinder finder) {

    public SdnModule {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(finder, "finder");
    }

    /** The module's name. */
    public String name() {
        return descriptor.name();
    }
}
