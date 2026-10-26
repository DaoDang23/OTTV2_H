import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Small child process used to exercise the parent process adapter in tests. */
public final class BotProtocolTestProcess {
    private BotProtocolTestProcess() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            while (input.readLine() != null) {
                if (mode.equals("large")) {
                    System.out.println("x".repeat(64 * 1024 + 1));
                } else {
                    System.out.println("{\"pieceId\":17,\"to\":[3,4]}");
                }
                System.out.flush();
            }
        }
    }
}
