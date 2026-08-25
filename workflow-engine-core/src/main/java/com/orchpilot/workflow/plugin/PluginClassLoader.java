package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.sdk.plugin.PluginApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Child-first class loader for one plugin version.
 *
 * <p><b>Why child-first.</b> The default parent-first order means a plugin can never use a different
 * version of a library than the engine. Reversing it lets two plugins bundle incompatible versions of an
 * HTTP client, and lets a plugin ship a library the engine does not have at all, without either being
 * able to disturb the engine. That is the whole point of loading plugins in their own loader.
 *
 * <p><b>The parent-first exceptions.</b> Three groups must come from the parent or the model collapses:
 * <ul>
 *   <li>JDK packages, which cannot be loaded twice;</li>
 *   <li>{@code org.slf4j}, so plugin logging reaches the engine's configured backend;</li>
 *   <li>{@link PluginApi#SHARED_PACKAGE}, the SDK. If a plugin bundled its own copy of the SDK, its
 *       {@code NodeExecutionResult} would be a different class from the engine's and every call would
 *       fail with a {@code ClassCastException} that looks impossible. This is why the SDK is declared
 *       {@code provided} in a plugin's POM and why it has no dependencies of its own.</li>
 * </ul>
 *
 * <p><b>This is not a security sandbox.</b> Class loader isolation separates <em>types</em>, not
 * <em>privileges</em>. Loaded plugin code runs with the engine's full authority: it can open sockets,
 * read files the process can read, start threads, call {@code System.exit} and use reflection to reach
 * anything on the class path. The {@code SecurityManager} that once constrained this is deprecated for
 * removal and is not a viable answer on Java 17. Treat plugins as trusted, reviewed code; when they
 * cannot be, run them in a separate process or container where the operating system can enforce limits.
 *
 * <p>Closing this loader releases the JAR file handles. The class loader itself becomes collectable only
 * once every reference to it, its classes and its instances is gone, which is why unloading also
 * unregisters node types and calls {@code destroy()} on the plugin.
 */
public class PluginClassLoader extends URLClassLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginClassLoader.class);

    /** Packages always delegated to the parent. */
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "org.ietf.jgss.", "org.slf4j.",
            PluginApi.SHARED_PACKAGE + ".");

    private final String coordinate;
    private final Set<String> additionalParentFirst;
    private final AtomicInteger locallyLoadedClasses = new AtomicInteger();

    /**
     * @param coordinate            {@code pluginId:version}, used in diagnostics and the loader name
     * @param urls                  the plugin JAR followed by any bundled library JARs
     * @param parent                the engine's class loader
     * @param additionalSharedPackages extra package prefixes to delegate parent-first, for deployments
     *                                 that intentionally share a library with plugins
     */
    public PluginClassLoader(String coordinate, URL[] urls, ClassLoader parent,
                             Collection<String> additionalSharedPackages) {
        super("plugin-" + coordinate, urls, parent);
        this.coordinate = coordinate;
        Set<String> extra = new LinkedHashSet<>();
        if (additionalSharedPackages != null) {
            for (String prefix : additionalSharedPackages) {
                if (prefix != null && !prefix.isBlank()) {
                    extra.add(prefix.endsWith(".") ? prefix : prefix + ".");
                }
            }
        }
        this.additionalParentFirst = Set.copyOf(extra);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> already = findLoadedClass(name);
            if (already != null) {
                return maybeResolve(already, resolve);
            }
            if (isParentFirst(name)) {
                try {
                    return maybeResolve(getParent().loadClass(name), resolve);
                } catch (ClassNotFoundException ex) {
                    // A shared package the parent does not actually have: fall through to the plugin.
                }
            }
            try {
                Class<?> local = findClass(name);
                locallyLoadedClasses.incrementAndGet();
                return maybeResolve(local, resolve);
            } catch (ClassNotFoundException ex) {
                // Not in the plugin: let the standard parent delegation have it.
                return super.loadClass(name, resolve);
            }
        }
    }

    @Override
    public URL getResource(String name) {
        URL fromPlugin = findResource(name);
        if (fromPlugin != null) {
            return fromPlugin;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Plugin resources first: ServiceLoader discovery of the plugin's own provider file depends on it.
        List<URL> ordered = new ArrayList<>();
        Enumeration<URL> fromPlugin = findResources(name);
        while (fromPlugin.hasMoreElements()) {
            ordered.add(fromPlugin.nextElement());
        }
        ClassLoader parent = getParent();
        if (parent != null) {
            Enumeration<URL> fromParent = parent.getResources(name);
            while (fromParent.hasMoreElements()) {
                URL url = fromParent.nextElement();
                if (!ordered.contains(url)) {
                    ordered.add(url);
                }
            }
        }
        return Collections.enumeration(ordered);
    }

    /**
     * @param className fully qualified class name
     * @return whether the class must come from the parent loader
     */
    boolean isParentFirst(String className) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : additionalParentFirst) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code pluginId:version} this loader serves */
    public String coordinate() {
        return coordinate;
    }

    /** @return how many classes were loaded from the plugin's own archives, for diagnostics */
    public int locallyLoadedClassCount() {
        return locallyLoadedClasses.get();
    }

    @Override
    public void close() throws IOException {
        super.close();
        log.debug("Closed class loader for plugin {} after loading {} local class(es)", coordinate,
                locallyLoadedClasses.get());
    }

    @Override
    public String toString() {
        return "PluginClassLoader{" + coordinate + ", localClasses=" + locallyLoadedClasses.get() + "}";
    }

    private Class<?> maybeResolve(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }
}
