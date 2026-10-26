package ottv2.engine.mapgen;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import ottv2.engine.model.Board;
import ottv2.engine.model.Position;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymmetricMapGeneratorTest {
    
    @Property(tries = 100)
    void generatedMapShouldAlwaysBeValid(@ForAll long seed) {
        MapConfig config = new MapConfig(10, 6, 4, 4);
        SymmetricMapGenerator generator = new SymmetricMapGenerator();
        Board board = generator.generate(seed, config);
        
        List<Position> startsA = Arrays.asList(new Position(8, 0), new Position(8, 1), new Position(8, 2), new Position(8, 3), new Position(8, 4), new Position(8, 5), new Position(8, 6), new Position(8, 7), new Position(8, 8));
        List<Position> startsB = Arrays.asList(new Position(0, 0), new Position(0, 1), new Position(0, 2), new Position(0, 3), new Position(0, 4), new Position(0, 5), new Position(0, 6), new Position(0, 7), new Position(0, 8));

        MapValidator.ValidationResult result = MapValidator.validate(board, startsA, startsB);
        assertTrue(result.valid(), "Map should be valid but failed: " + result.reason());
    }
}
