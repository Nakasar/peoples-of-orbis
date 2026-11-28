package dev.nakasar.hytale.peuples.reputation;

/**
 * Déclin de la réputation de village pendant l'absence du joueur (Kevin, 28 novembre 2026), compté en <b>temps réel</b>
 * et non en jours de jeu : le monde continue de tourner sans joueur (veille), et un jour de jeu ne dure que quelques
 * dizaines de minutes.
 * <ol>
 *   <li>pendant {@value #DECAY_DAYS} jours réels d'absence, la réputation descend régulièrement jusqu'au minimum du rang
 *       que le joueur avait en partant (Ami : 200, Toléré : 50…) ;</li>
 *   <li>elle reste à ce minimum {@value #HOLD_DAYS} jours réels de plus ;</li>
 *   <li>au terme, le joueur passe au rang précédent (une réputation sous ce minimum), et le cycle recommence dans ce
 *       rang.</li>
 * </ol>
 * Le déclin ne fait jamais tomber sous le rang Étranger : un Toléré absent finit Étranger, jamais Honni ; un Étranger
 * ou un Honni ne perd rien. Être dans le village arrête l'horloge : seule l'absence coûte.
 */
public final class AbsenceDecay {

    /** Jours réels pour descendre jusqu'au minimum du rang. */
    public static final int DECAY_DAYS = 7;
    /** Jours réels passés au minimum du rang avant de passer au rang précédent. */
    public static final int HOLD_DAYS = 14;

    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long DECAY_MS = DECAY_DAYS * DAY_MS;
    static final long HOLD_MS = HOLD_DAYS * DAY_MS;

    private AbsenceDecay() {
    }

    /** Réputation après `awayMs` millisecondes réelles d'absence, partant de `value`. */
    public static int decayed(int value, long awayMs) {
        long left = Math.max(0, awayMs);
        int v = value;
        while (left > 0) {
            Rank rank = Rank.of(v);
            if (rank.ordinal() <= Rank.ETRANGER.ordinal()) {
                return v;
            }
            int floor = rank.min();
            if (left < DECAY_MS) {
                // Descente régulière : la part de la semaine écoulée, de la valeur au départ jusqu'au minimum du rang.
                return v - (int) ((v - floor) * left / DECAY_MS);
            }
            left -= DECAY_MS;
            v = floor;
            if (left < HOLD_MS) {
                return v;
            }
            left -= HOLD_MS;
            v = floor - 1;
        }
        return v;
    }
}
