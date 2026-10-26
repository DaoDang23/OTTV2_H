package ottv2.worker.sandbox;

import java.io.IOException;
import java.util.List;

/** DEVELOPMENT ONLY: bot code runs directly on the host without any isolation. */
public final class LocalProcessSandbox implements Sandbox {
    public LocalProcessSandbox() {
        System.err.println("WARNING: LocalProcessSandbox is UNSAFE; bot code runs directly on the host.");
    }

    @Override
    public Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }
}
