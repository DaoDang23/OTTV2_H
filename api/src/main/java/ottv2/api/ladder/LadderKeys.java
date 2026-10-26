package ottv2.api.ladder;

final class LadderKeys {
    private LadderKeys() { }
    static String presence(long seasonId) { return "ottv2:ladder:" + seasonId + ":presence"; }
    static String submissions(long seasonId) { return "ottv2:ladder:" + seasonId + ":submissions"; }
    static String ratings(long seasonId) { return "ottv2:ladder:" + seasonId + ":ratings"; }
}
