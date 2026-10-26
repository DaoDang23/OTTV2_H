package ottv2.api.tournament;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TournamentBroadcaster {
    private final SimpMessagingTemplate messaging;

    public TournamentBroadcaster(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    public void publishBracketChanged(long tournamentId) {
        Runnable send = () -> {
            BracketChanged msg = new BracketChanged(tournamentId, "BRACKET_UPDATED");
            messaging.convertAndSend("/topic/tournaments/" + tournamentId, msg);
            messaging.convertAndSend("/topic/tournament/" + tournamentId, msg);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    public record BracketChanged(long tournamentId, String type) { }
}
