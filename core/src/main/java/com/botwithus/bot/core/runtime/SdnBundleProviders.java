package com.botwithus.bot.core.runtime;

import com.botwithus.bot.core.crypto.SdnModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Finds the providers of a service in one delivered SDN jar.
 *
 * <p>A jar that declares a module is loaded the way a local script is: in a
 * {@link ModuleLayer} of its own, so its {@code requires} and {@code exports}
 * are enforced and its providers come from its {@code provides} clause. The
 * layer maps the module to the jar's own loader, because the runtime decrypts
 * the jar inside that loader and nowhere else. A jar with no module descriptor
 * loads as before, through {@code META-INF/services} into the loader's unnamed
 * module.
 *
 * <p>A modular jar that does not resolve against the parent layer, for example
 * because it requires a module the host does not have, throws. It is not
 * retried as an unnamed module: that would run it without the module boundary
 * its author declared.
 *
 * <p>Only providers defined by the jar's own loader are returned, in both
 * modes. A service lookup also walks the parent layers and loaders, and a
 * provider found there is not this jar's, so crediting it to the jar would pair
 * the wrong script with the jar's catalogue entry.
 */
final class SdnBundleProviders {

    private static final Logger log = LoggerFactory.getLogger(SdnBundleProviders.class);

    private SdnBundleProviders() {
    }

    /**
     * The providers of {@code service} in the jar behind {@code loader}.
     *
     * @param loader  the jar's loader
     * @param module  the module the jar declares, or empty to load it unnamed
     * @param parent  the layer the jar's module resolves against
     * @param service the service type
     * @throws RuntimeException when the module does not resolve or a provider
     *                          cannot be instantiated
     */
    static <S> List<S> load(ClassLoader loader, Optional<SdnModule> module, ModuleLayer parent,
                            Class<S> service) {
        return providers(loader, module, parent, service).stream().map(ServiceLoader.Provider::get).toList();
    }

    /**
     * As {@link #load}, but the providers themselves rather than one instance of
     * each. {@link ServiceLoader.Provider#get()} makes a new instance on every call,
     * which is how each client gets its own copy of a delivered script while the
     * jar itself is only ever defined once, in memory.
     */
    static <S> List<ServiceLoader.Provider<S>> providers(ClassLoader loader, Optional<SdnModule> module,
                                                         ModuleLayer parent, Class<S> service) {
        if (module.isPresent()) {
            return fromModule(loader, module.get(), parent, service);
        }
        return fromUnnamed(loader, service);
    }

    private static <S> List<ServiceLoader.Provider<S>> fromModule(ClassLoader loader, SdnModule module,
                                                                  ModuleLayer parent, Class<S> service) {
        Configuration cf = parent.configuration()
                .resolveAndBind(module.finder(), ModuleFinder.of(), Set.of(module.name()));
        ModuleLayer layer = ModuleLayer.defineModules(cf, List.of(parent), name -> loader).layer();
        List<ServiceLoader.Provider<S>> found = ServiceLoader.load(layer, service).stream()
                .filter(provider -> provider.type().getModule().getLayer() == layer)
                .toList();
        found.forEach(p -> log.info("SDN: loaded {} from module {}", p.type().getName(), module.name()));
        return found;
    }

    private static <S> List<ServiceLoader.Provider<S>> fromUnnamed(ClassLoader loader, Class<S> service) {
        List<ServiceLoader.Provider<S>> found = ServiceLoader.load(service, loader).stream()
                .filter(provider -> provider.type().getClassLoader() == loader)
                .toList();
        found.forEach(p -> log.info("SDN: loaded {} (no module descriptor)", p.type().getName()));
        return found;
    }
}
