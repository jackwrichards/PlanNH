import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

/**
 * Pushes recompiled classes into the running dev client over JDWP (see docs/dev-harness.md, "Hot swap").
 *
 * <pre>
 * java tools/dev/Hotswap.java baseline &lt;classesDir&gt; &lt;manifest&gt;        record what the game was launched with
 * java tools/dev/Hotswap.java push &lt;classesDir&gt; &lt;manifest&gt; [port]   redefine classes whose bytes changed since
 * java tools/dev/Hotswap.java info &lt;className&gt; [port]                 where/how a class is loaded
 * </pre>
 *
 * Changes are found by content hash, not timestamp: annotation processors force full recompiles, and redefining
 * hundreds of identical classes in one batch crashes JBR's enhanced redefinition.
 *
 * Exit codes: 0 swapped (or nothing to do), 2 some changed classes are not loaded yet (they will load from the
 * stale jar: restart), 1 failure.
 */
public class Hotswap {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "baseline" -> baseline(Path.of(args[1]), Path.of(args[2]));
            case "push" -> System.exit(push(Path.of(args[1]), Path.of(args[2]), port(args, 3)));
            case "info" -> info(args[1], port(args, 2));
            default -> usage();
        }
    }

    private static void baseline(Path classesDir, Path manifest) throws Exception {
        final Map<String, Path> classes = loadableClasses(classesDir);
        final Map<String, String> sums = new TreeMap<>();
        for (final Map.Entry<String, Path> e : classes.entrySet()) sums.put(e.getKey(), sha1(e.getValue()));
        writeManifest(manifest, sums);
        System.out.println("hotswap: baseline of " + sums.size() + " classes");
    }

    private static int push(Path classesDir, Path manifest, int port) throws Exception {
        if (!Files.exists(manifest)) {
            System.err.println("hotswap: no baseline at " + manifest + " (the client records one when it starts)");
            return 1;
        }
        final Map<String, String> sums = readManifest(manifest);
        final Map<String, Path> changed = new LinkedHashMap<>();
        for (final Map.Entry<String, Path> e : loadableClasses(classesDir).entrySet()) {
            if (!sha1(e.getValue()).equals(sums.get(e.getKey()))) changed.put(e.getKey(), e.getValue());
        }
        if (changed.isEmpty()) {
            System.out.println("hotswap: no changed classes");
            return 0;
        }
        final VirtualMachine vm = attach(port);
        final List<String> notLoaded = new ArrayList<>();
        try {
            final Map<ReferenceType, byte[]> redefine = new LinkedHashMap<>();
            for (final Map.Entry<String, Path> e : changed.entrySet()) {
                final List<ReferenceType> loaded = vm.classesByName(e.getKey());
                if (loaded.isEmpty()) {
                    notLoaded.add(e.getKey());
                    continue;
                }
                final byte[] bytes = Files.readAllBytes(e.getValue());
                for (final ReferenceType type : loaded) redefine.put(type, bytes);
            }
            if (!redefine.isEmpty()) vm.redefineClasses(redefine);
            System.out.println("hotswap: redefined " + redefine.size() + " class(es)");
            for (final ReferenceType type : redefine.keySet()) {
                System.out.println("  " + type.name());
                sums.put(type.name(), sha1(changed.get(type.name())));
            }
        } finally {
            vm.dispose();
        }
        writeManifest(manifest, sums);
        if (!notLoaded.isEmpty()) {
            // Left out of the manifest on purpose: they keep showing up until a restart loads the new jar.
            System.out.println("hotswap: changed but not loaded yet, restart to pick these up:");
            notLoaded.forEach(n -> System.out.println("  " + n));
            return 2;
        }
        return 0;
    }

    private static void info(String className, int port) throws Exception {
        final VirtualMachine vm = attach(port);
        try {
            System.out.println(vm.name() + " " + vm.version() + " canRedefine=" + vm.canRedefineClasses());
            for (final ReferenceType t : vm.classesByName(className)) {
                System.out.println(
                    t.name() + " classfile=" + t.majorVersion() + "." + t.minorVersion() + " loader="
                        + (t.classLoader() == null ? "boot" : t.classLoader().referenceType().name()));
            }
        } finally {
            vm.dispose();
        }
    }

    /**
     * Class name -> the bytes the game loads for it. {@code classesDir} is laid out like a multi-release jar (the
     * JVM Downgrader output): {@code META-INF/versions/N/X.class} wins for the highest N, else the base X.class.
     */
    private static Map<String, Path> loadableClasses(Path classesDir) throws IOException {
        final Path versioned = highestVersionDir(classesDir);
        final Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (final Path file : files.filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !classesDir.relativize(p).startsWith("META-INF"))
                .toList()) {
                final Path rel = classesDir.relativize(file);
                final Path override = versioned == null ? null : versioned.resolve(rel);
                out.put(className(rel), override != null && Files.exists(override) ? override : file);
            }
        }
        return out;
    }

    private static Path highestVersionDir(Path classesDir) throws IOException {
        final Path versions = classesDir.resolve("META-INF/versions");
        if (!Files.isDirectory(versions)) return null;
        try (Stream<Path> dirs = Files.list(versions)) {
            return dirs.filter(p -> p.getFileName().toString().matches("\\d+"))
                .max((a, b) -> Integer.parseInt(a.getFileName().toString())
                    - Integer.parseInt(b.getFileName().toString()))
                .orElse(null);
        }
    }

    private static VirtualMachine attach(int port) throws Exception {
        final AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors()
            .stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow();
        final Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(Integer.toString(port));
        args.get("timeout").setValue("5000");
        return connector.attach(args);
    }

    private static String className(Path rel) {
        final String s = rel.toString().replace('\\', '/');
        return s.substring(0, s.length() - ".class".length()).replace('/', '.');
    }

    private static String sha1(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
    }

    private static Map<String, String> readManifest(Path manifest) throws IOException {
        final Map<String, String> sums = new TreeMap<>();
        for (final String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            final int space = line.indexOf(' ');
            if (space > 0) sums.put(line.substring(0, space), line.substring(space + 1));
        }
        return sums;
    }

    private static void writeManifest(Path manifest, Map<String, String> sums) throws IOException {
        Files.createDirectories(manifest.getParent());
        final StringBuilder sb = new StringBuilder();
        sums.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
        Files.writeString(manifest, sb.toString(), StandardCharsets.UTF_8);
    }

    private static int port(String[] args, int index) {
        return args.length > index ? Integer.parseInt(args[index]) : 5005;
    }

    private static void usage() {
        System.err.println(
            "usage: Hotswap baseline <classesDir> <manifest> | push <classesDir> <manifest> [port] | info <class> [port]");
        System.exit(1);
    }
}
