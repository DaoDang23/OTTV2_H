package ottv2.api.tournament;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BracketBuilderTest {
    @Test
    void buildsThirtyOneNodesAndUsesTheDocumentedFirstRoundOrder() {
        List<BracketBuilder.Entry> entries = IntStream.rangeClosed(1, 32)
                .mapToObj(seed -> new BracketBuilder.Entry(seed, seed * 10L, seed * 100L)).toList();
        var bracket = new BracketBuilder().build(entries);
        assertEquals(31, bracket.nodes().size());
        assertEquals(10L, bracket.nodes().getFirst().botAId());
        assertEquals(3200L, bracket.nodes().getFirst().submissionBId());
        assertEquals('A', bracket.nodes().getFirst().nextSide());
        assertEquals('B', bracket.nodes().get(1).nextSide());
        assertEquals(1, bracket.nodes().get(15).roundNo());
        assertEquals(2, bracket.nodes().get(16).roundNo());
        assertEquals(5, bracket.nodes().getLast().roundNo());
    }

    @Test
    void rejectsIncompleteOrDuplicateSeeds() {
        BracketBuilder builder = new BracketBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of()));
        List<BracketBuilder.Entry> duplicate = IntStream.rangeClosed(1, 32)
                .mapToObj(seed -> new BracketBuilder.Entry(seed == 32 ? 31 : seed, seed, seed)).toList();
        assertThrows(IllegalArgumentException.class, () -> builder.build(duplicate));
    }
}
