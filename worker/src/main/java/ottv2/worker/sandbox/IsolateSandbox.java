package ottv2.worker.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/** Linux isolate sandbox. The worker must initialize and own the configured box. */
public final class IsolateSandbox implements Sandbox {
    private final String executable;
    private final int boxId;
    private final long cpuSeconds;
    private final long wallSeconds;
    private final int memoryMegabytes;
    private final int maxProcesses;
    private final Path boxDirectory;

    public IsolateSandbox(String executable, int boxId, long cpuSeconds, long wallSeconds,
                          int memoryMegabytes, int maxProcesses) {
        this(executable, boxId, cpuSeconds, wallSeconds, memoryMegabytes, maxProcesses,
                Path.of("/var/lib/isolate", Integer.toString(boxId), "box"));
    }

    public IsolateSandbox(String executable, int boxId, long cpuSeconds, long wallSeconds,
                          int memoryMegabytes, int maxProcesses, Path boxDirectory) {
        if (boxId < 0 || cpuSeconds < 1 || wallSeconds < 1 || memoryMegabytes < 1 || maxProcesses < 1) {
            throw new IllegalArgumentException("Invalid isolate limits");
        }
        this.executable = executable;
        this.boxId = boxId;
        this.cpuSeconds = cpuSeconds;
        this.wallSeconds = wallSeconds;
        this.memoryMegabytes = memoryMegabytes;
        this.maxProcesses = maxProcesses;
        this.boxDirectory = boxDirectory;
    }

    public static IsolateSandbox botDefaults(int boxId) {
        // A bot is a persistent process for the whole match. Per-turn wall-clock
        // timeout is enforced by ProcessBotController; isolate's CPU limit is match-wide.
        return new IsolateSandbox("isolate", boxId, 200, 600, 256, 16);
    }

    @Override
    public Process start(List<String> command) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new IOException("isolate sandbox requires Linux (use WSL2 or Docker on Windows)");
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add(executable);
        wrapped.add("--box-id=" + boxId);
        wrapped.add("--cg");
        wrapped.add("--env=PATH=/usr/bin:/bin");
        Path metaPath = getMetaPath();
        wrapped.add("--meta=" + metaPath.toAbsolutePath().toString());
        wrapped.add("--run");
        wrapped.add("--time=" + cpuSeconds);
        wrapped.add("--wall-time=" + wallSeconds);
        long addressSpaceMegabytes = isJavaJarCommand(command) ? Math.max(memoryMegabytes, 1024) : memoryMegabytes;
        wrapped.add("--mem=" + (addressSpaceMegabytes * 1024));
        wrapped.add("--cg-mem=" + (memoryMegabytes * 1024));
        wrapped.add("--processes=" + maxProcesses);
        // Keep interpreter and dynamic-linker files visible inside the sandbox.
        // Isolate mounts several of these by default, but declaring them here
        // makes the runtime dependencies explicit; directory rules are read-only.
        wrapped.add("--dir=/usr");
        wrapped.add("--dir=/lib");
        wrapped.add("--dir=/lib64");
        wrapped.add("--dir=/bin");
        wrapped.add("--dir=/etc");
        wrapped.add("--");
        wrapped.addAll(stageCommand(command));
        ProcessBuilder processBuilder = new ProcessBuilder(wrapped);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return processBuilder.start();
    }

    @Override
    public Path getMetaPath() {
        return boxDirectory.resolve("meta.txt");
    }

    private List<String> stageCommand(List<String> command) throws IOException {
        if (!Files.isDirectory(boxDirectory)) {
            throw new IOException("Isolate box directory is missing or not initialized: " + boxDirectory);
        }
        List<String> staged = new ArrayList<>(command);
        int fileArgument = -1;
        boolean pythonCommand = command.size() >= 2 && command.get(0).equals("/usr/bin/python3");
        if (pythonCommand) fileArgument = command.size() >= 3 && command.get(1).equals("-u") ? 2 : 1;
        else if (isJavaJarCommand(command)) {
            fileArgument = command.indexOf("-jar") + 1;
        } else if (command.get(0).equals("./bot") || command.get(0).equals("bot")) {
            fileArgument = 0;
        } else if (Path.of(command.get(0)).isAbsolute()) fileArgument = 0;
        if (fileArgument < 0 || fileArgument >= command.size()) {
            throw new IOException("Expected a bot executable, Python script/package, or JAR");
        }

        Path source;
        if (command.get(fileArgument).equals("./bot") || command.get(fileArgument).equals("bot")) {
            source = boxDirectory.resolve("bot");
        } else {
            source = Path.of(command.get(fileArgument)).toAbsolutePath().normalize();
        }
        if (!Files.exists(source)) throw new IOException("Bot artifact is missing: " + source);
        if (Files.isSymbolicLink(source)) throw new IOException("Bot artifact cannot be a symbolic link: " + source);

        boolean sourceIsDirectory = Files.isDirectory(source);
        if (sourceIsDirectory && !pythonCommand) {
            throw new IOException("Bot directories must be launched with /usr/bin/python3 and contain __main__.py");
        }
        boolean copyPythonDirectory = pythonCommand && !sourceIsDirectory;
        Path sourceRoot = sourceIsDirectory ? source
                : copyPythonDirectory ? source.getParent() : source;

        if (!pythonCommand && !isJavaJarCommand(command)) {
            // C++ binary executable: stage directly to /box/bot and invoke with ./bot
            Path stagedFile = boxDirectory.resolve("bot");
            if (!source.equals(stagedFile)) {
                Files.copy(source, stagedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            stagedFile.toFile().setExecutable(true, true);
            staged.set(0, "./bot");
            return staged;
        }

        Path destinationRoot = boxDirectory.resolve("bot-" + boxId).normalize();
        if (!destinationRoot.getParent().equals(boxDirectory.normalize())) {
            throw new IOException("Invalid bot staging directory: " + destinationRoot);
        }
        deleteTree(destinationRoot);

        Path entryRelative;
        if (sourceIsDirectory || copyPythonDirectory) {
            copyTree(sourceRoot, destinationRoot);
            entryRelative = sourceIsDirectory ? Path.of("") : sourceRoot.relativize(source);
        } else {
            Files.createDirectories(destinationRoot);
            Path stagedFile = destinationRoot.resolve(source.getFileName());
            Files.copy(source, stagedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            entryRelative = source.getFileName();
            if (fileArgument == 0) stagedFile.toFile().setExecutable(true, true);
        }

        String stagedPath = "/box/" + destinationRoot.getFileName();
        if (!entryRelative.toString().isEmpty()) {
            stagedPath += "/" + entryRelative.toString().replace('\\', '/');
        }
        staged.set(fileArgument, stagedPath);
        return staged;
    }

    private static boolean isJavaJarCommand(List<String> command) {
        return command.size() >= 3
                && Path.of(command.get(0)).getFileName().toString().equals("java")
                && command.contains("-jar");
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("Bot directory contains a symbolic link: " + dir);
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
                    throw new IOException("Bot directory contains an unsupported file: " + file);
                }
                Files.copy(file, destination.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void cleanup() throws IOException {
        runIsolateAction("--cleanup");
        // Reinitialize the empty box so this sandbox instance can run a later match.
        runIsolateAction("--init");
    }

    private void runIsolateAction(String action) throws IOException {
        Process cleanup = new ProcessBuilder(executable, "--box-id=" + boxId, "--cg", action).start();
        try {
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
                throw new IOException("Timed out running isolate " + action + " for box " + boxId);
            }
            if (cleanup.exitValue() != 0) throw new IOException("isolate " + action + " failed for box " + boxId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanup.destroyForcibly();
            throw new IOException("Interrupted running isolate " + action + " for box " + boxId, e);
        }
    }
}
