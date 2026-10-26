package ottv2.worker.match;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded in-memory LRU cache for immutable compiled artifacts. */
public final class ArtifactCache {
    private final long maxBytes;
    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long currentBytes;

    public ArtifactCache(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Cache size must be positive");
        this.maxBytes = maxBytes;
    }

    public synchronized byte[] get(String key, ArtifactLoader loader) throws Exception {
        byte[] existing = entries.get(key);
        if (existing != null) return existing.clone();
        byte[] loaded = loader.load();
        if (loaded == null || loaded.length == 0) throw new IllegalArgumentException("Downloaded artifact is empty");
        byte[] copy = loaded.clone();
        if (copy.length <= maxBytes) {
            entries.put(key, copy);
            currentBytes += copy.length;
            while (currentBytes > maxBytes) {
                Map.Entry<String, byte[]> eldest = entries.entrySet().iterator().next();
                currentBytes -= eldest.getValue().length;
                entries.remove(eldest.getKey());
            }
        }
        return copy.clone();
    }

    public synchronized long currentBytes() { return currentBytes; }
    public synchronized int size() { return entries.size(); }

    @FunctionalInterface
    public interface ArtifactLoader { byte[] load() throws Exception; }
}
