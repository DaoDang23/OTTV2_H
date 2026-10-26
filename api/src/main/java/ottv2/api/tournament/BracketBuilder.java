package ottv2.api.tournament;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a 32-team single-elimination tree with the published seed order. */
public final class BracketBuilder {
    private static final int[][] FIRST_ROUND = {
            {1, 32}, {16, 17}, {8, 25}, {9, 24}, {4, 29}, {13, 20}, {5, 28}, {12, 21},
            {2, 31}, {15, 18}, {7, 26}, {10, 23}, {3, 30}, {14, 19}, {6, 27}, {11, 22}
    };

    public Bracket build(List<Entry> entries) {
        if (entries.size() != 32) throw new IllegalArgumentException("A 32-team bracket requires exactly 32 entries");
        Map<Integer, Entry> bySeed = new HashMap<>();
        for (Entry entry : entries) {
            if (entry.seedNo() < 1 || entry.seedNo() > 32 || bySeed.put(entry.seedNo(), entry) != null) {
                throw new IllegalArgumentException("Tournament seeds must be unique and range from 1 to 32");
            }
        }
        List<Node> nodes = new ArrayList<>(31);
        for (int i = 0; i < FIRST_ROUND.length; i++) {
            int[] pair = FIRST_ROUND[i];
            Entry a = require(bySeed, pair[0]);
            Entry b = require(bySeed, pair[1]);
            nodes.add(new Node(1, i + 1, a.botId(), b.botId(), a.submissionId(), b.submissionId(), 2, (i / 2) + 1,
                    i % 2 == 0 ? 'A' : 'B'));
        }
        for (int round = 2; round <= 5; round++) {
            int slots = 32 >> round;
            int childSlots = slots * 2;
            for (int slot = 1; slot <= slots; slot++) {
                nodes.add(new Node(round, slot, null, null, null, null, round == 5 ? null : round + 1,
                        round == 5 ? null : (slot + 1) / 2,
                        round == 5 ? null : (slot % 2 == 1 ? 'A' : 'B')));
            }
            if (nodes.size() > 31 || childSlots > 32) throw new IllegalStateException("Invalid bracket tree size");
        }
        return new Bracket(List.copyOf(nodes));
    }

    private static Entry require(Map<Integer, Entry> entries, int seed) {
        Entry entry = entries.get(seed);
        if (entry == null) throw new IllegalArgumentException("Missing tournament seed " + seed);
        return entry;
    }

    public record Entry(int seedNo, long botId, long submissionId) { }
    public record Node(int roundNo, int slotNo, Long botAId, Long botBId,
                       Long submissionAId, Long submissionBId, Integer nextRoundNo,
                       Integer nextSlotNo, Character nextSide) { }
    public record Bracket(List<Node> nodes) { }
}
