package ottv2.worker.sandbox;

import java.io.IOException;
import java.util.List;

/** Starts an untrusted bot process under a specific isolation policy. */
public interface Sandbox {
    Process start(List<String> command) throws IOException;

    default void cleanup() throws IOException {
        // Most local development sandboxes have no external resources to release.
    }

    default java.nio.file.Path getMetaPath() {
        return null;
    }
}
