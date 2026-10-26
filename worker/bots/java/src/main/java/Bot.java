import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class Bot {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Implement this function. Return null only if the protocol is extended to support pass.
    static JsonNode decide(JsonNode state) {
        JsonNode piece = state.path("yourPieces").path(0);
        if (piece.isMissingNode()) throw new IllegalArgumentException("No available pieces");
        int r = piece.path("r").asInt(), c = piece.path("c").asInt();
        ObjectNode move = JSON.createObjectNode();
        move.put("pieceId", piece.path("id").asInt());
        move.putArray("to").add(r > 0 ? r - 1 : r + 1).add(c);
        return move;
    }

    public static void main(String[] args) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = input.readLine()) != null) {
                try { System.out.println(JSON.writeValueAsString(decide(JSON.readTree(line)))); System.out.flush(); }
                catch (Exception e) { System.err.println(e.getMessage()); System.err.flush(); break; }
            }
        }
    }
}
