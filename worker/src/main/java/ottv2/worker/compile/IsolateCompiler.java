package ottv2.worker.compile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Compiles student-controlled code inside a dedicated isolate box. */
final class IsolateCompiler implements BotCompiler {
    private static final int LOG_LIMIT = 64 * 1024;
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    private static final Duration WALL_LIMIT = Duration.ofSeconds(65);
    private final String isolate;
    private final int boxId;
    private final Path isolateRoot;
    private final Path workerJar;
    private final int maxSourceBytes;

    IsolateCompiler(String isolate, int firstBoxId, String workerJar, int maxSourceBytes) {
        this(isolate, firstBoxId, Path.of(System.getenv().getOrDefault("ISOLATE_ROOT", "/var/lib/isolate")),
                Path.of(workerJar), maxSourceBytes);
    }

    IsolateCompiler(String isolate, int firstBoxId, Path isolateRoot, Path workerJar, int maxSourceBytes) {
        if (firstBoxId < 0 || maxSourceBytes < 1) throw new IllegalArgumentException("Invalid compiler settings");
        this.isolate = isolate;
        this.boxId = firstBoxId;
        this.isolateRoot = isolateRoot;
        this.workerJar = workerJar.toAbsolutePath().normalize();
        this.maxSourceBytes = maxSourceBytes;
    }

    @Override
    public CompileOutcome compile(CompileJob job, byte[] source) throws Exception {
        if (source.length > maxSourceBytes) {
            return failed(job, "Source exceeds configured size limit");
        }
        String language = job.language().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("JAVA", "CPP", "PYTHON").contains(language)) {
            return failed(job, "Unsupported language: " + language);
        }
        if (language.equals("JAVA") && !Files.isRegularFile(workerJar)) {
            throw new IllegalStateException("Worker jar needed for the Java bot JSON protocol is missing: " + workerJar);
        }

        Path box = isolateRoot.resolve(Integer.toString(boxId)).resolve("box");
        Path work = box.resolve("compile-work");
        boolean initialized = false;
        try {
            runAction(boxId, "--cleanup", true);
            runAction(boxId, "--init", false);
            initialized = true;
            Files.createDirectories(work.resolve("tmp"));
            byte[] output;
            String log;
            if (language.equals("PYTHON")) {
                Path script = work.resolve("bot.py");
                Files.write(script, source);
                ProcessResult process = run(boxId, List.of("/usr/bin/python3", "-I", "-m", "py_compile", inside("bot.py")), 256);
                log = process.output();
                if (process.exitCode() != 0) return failed(job, log);
                output = Files.readAllBytes(script);
            } else if (language.equals("CPP")) {
                Path sourcePath = work.resolve("bot.cpp");
                Files.write(sourcePath, source);
                ProcessResult process = run(boxId, List.of("/usr/bin/g++", "-O3", "-I/usr/include",
                        "-o", inside("bot"), inside("bot.cpp")), 1024);
                log = process.output();
                if (process.exitCode() != 0) return failed(job, log);
                if (Files.size(work.resolve("bot")) > MAX_ARTIFACT_BYTES) {
                    return failed(job, "Compiled C++ artifact exceeds 64 MiB limit\n" + log);
                }
                output = Files.readAllBytes(work.resolve("bot"));
            } else {
                Path sourcePath = work.resolve("Bot.java");
                Files.write(sourcePath, source);
                Files.copy(workerJar, work.resolve("worker-classpath.jar"), StandardCopyOption.REPLACE_EXISTING);
                Files.createDirectories(work.resolve("classes"));
                ProcessResult process = run(boxId, List.of("/usr/bin/javac", "-proc:none", "-J-Xms16m", "-J-Xmx512m",
                        "-cp", inside("worker-classpath.jar"), "-d", inside("classes"), inside("Bot.java")), 1024);
                log = process.output();
                if (process.exitCode() != 0) return failed(job, log);
                if (!Files.isRegularFile(work.resolve("classes/Bot.class"))) {
                    return failed(job, "Java source must define a top-level Bot class with a main method.\n" + log);
                }
                output = buildJavaArtifact(work.resolve("classes"));
                if (output.length > MAX_ARTIFACT_BYTES) return failed(job, "Compiled Java artifact exceeds 64 MiB limit\n" + log);
            }
            String artifactKey = "artifacts/" + job.sourceSha256() + "/" + language.toLowerCase(java.util.Locale.ROOT);
            return new CompileOutcome(result(job, "COMPILED", artifactKey, log), output);
        } finally {
            if (initialized) {
                try {
                    runAction(boxId, "--cleanup", true);
                    runAction(boxId, "--init", true);
                } catch (Exception ignored) { }
            }
        }
    }

    private byte[] buildJavaArtifact(Path classes) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Bot");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            try (var paths = Files.walk(classes)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String name = classes.relativize(file).toString().replace('\\', '/');
                    addFile(jar, name, file);
                }
            }
            try (JarFile sourceJar = new JarFile(workerJar.toFile())) {
                Enumeration<JarEntry> entries = sourceJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !isJacksonClass(entry.getName())) continue;
                    jar.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream in = sourceJar.getInputStream(entry)) { in.transferTo(jar); }
                    jar.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private static boolean isJacksonClass(String name) {
        return (name.startsWith("com/fasterxml/jackson/annotation/")
                || name.startsWith("com/fasterxml/jackson/core/")
                || name.startsWith("com/fasterxml/jackson/databind/")) && name.endsWith(".class");
    }

    private static void addFile(JarOutputStream jar, String name, Path file) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        Files.copy(file, jar);
        jar.closeEntry();
    }

    private ProcessResult run(int boxId, List<String> command, int memoryMb) throws Exception {
        List<String> args = new ArrayList<>(List.of(isolate, "--box-id=" + boxId, "--cg",
                "--env=PATH=/usr/bin:/bin", "--env=TMPDIR=/box/compile-work/tmp",
                "--time=30", "--wall-time=60", "--mem=" + (memoryMb * 1024),
                "--cg-mem=" + (memoryMb * 1024), "--processes=64", "--fsize=131072",
                "--dir=/usr", "--dir=/bin", "--dir=/lib", "--dir=/lib64", "--run", "--"));
        args.addAll(command);
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
        if (!process.waitFor(WALL_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            return new ProcessResult(124, output.get(3, TimeUnit.SECONDS) + "\nCompilation timed out.\n");
        }
        return new ProcessResult(process.exitValue(), output.get(3, TimeUnit.SECONDS));
    }

    private static String readBounded(InputStream input) {
        try (input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int kept = 0;
            boolean truncated = false;
            while ((read = input.read(buffer)) >= 0) {
                int copy = Math.min(read, LOG_LIMIT - kept);
                if (copy > 0) { bytes.write(buffer, 0, copy); kept += copy; }
                if (copy < read) truncated = true;
            }
            String value = bytes.toString(StandardCharsets.UTF_8);
            return truncated ? value + "\n[compiler output truncated]\n" : value;
        } catch (Exception e) {
            return "Could not read compiler output: " + e.getMessage();
        }
    }

    private void runAction(int boxId, String action, boolean ignoreFailure) throws Exception {
        Process process = new ProcessBuilder(isolate, "--box-id=" + boxId, "--cg", action).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out running isolate " + action);
        }
        if (!ignoreFailure && process.exitValue() != 0) {
            String error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("isolate " + action + " failed: " + error);
        }
    }

    private static String inside(String file) { return "/box/compile-work/" + file; }
    private static CompileOutcome failed(CompileJob job, String log) {
        return new CompileOutcome(result(job, "COMPILE_ERROR", null, log), null);
    }
    private static CompileResult result(CompileJob job, String status, String artifact, String log) {
        return new CompileResult(job.submissionId(), status, artifact, truncate(log));
    }
    private static String truncate(String log) {
        if (log == null) return "";
        return log.length() <= LOG_LIMIT ? log : log.substring(0, LOG_LIMIT);
    }

    private record ProcessResult(int exitCode, String output) { }
}
