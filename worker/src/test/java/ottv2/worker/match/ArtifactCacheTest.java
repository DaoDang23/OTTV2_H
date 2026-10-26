package ottv2.worker.match;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactCacheTest {
    @Test
    void servesCopiesAndEvictsLeastRecentlyUsedArtifactsToStayWithinLimit() throws Exception {
        ArtifactCache cache = new ArtifactCache(6);
        AtomicInteger loads = new AtomicInteger();
        byte[] one = cache.get("one", () -> { loads.incrementAndGet(); return new byte[]{1, 1, 1}; });
        one[0] = 9;
        assertArrayEquals(new byte[]{1, 1, 1}, cache.get("one", () -> { loads.incrementAndGet(); return new byte[0]; }));
        cache.get("two", () -> { loads.incrementAndGet(); return new byte[]{2, 2, 2}; });
        cache.get("one", () -> { loads.incrementAndGet(); return new byte[0]; }); // make "one" most recently used
        cache.get("three", () -> { loads.incrementAndGet(); return new byte[]{3, 3, 3}; });

        assertEquals(6, cache.currentBytes());
        assertEquals(2, cache.size());
        cache.get("two", () -> { loads.incrementAndGet(); return new byte[]{2, 2, 2}; });
        assertEquals(4, loads.get());
    }
}
