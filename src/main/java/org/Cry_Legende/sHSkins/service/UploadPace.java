package org.Cry_Legende.sHSkins.service;

import java.util.ArrayDeque;

/**
 * Rythme des depots d'un compte : un nombre par minute glissante, un ecart entre deux, et une pause de plus en plus longue apres
 * chaque refus. Les depots qui ne pressent pas n'en prennent que la moitie : il reste toujours de quoi faire un skin tout de suite.
 */
final class UploadPace {

    private static final long WINDOW_MS = 60_000;
    private static final long GAP_MS = 1_200;
    private static final long PAUSE_MS = 2 * 60_000;
    private static final long PAUSE_MAX_MS = 60 * 60_000;

    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private int perMinute;
    private long pausedUntil;
    private int refusals;

    UploadPace(int perMinute) {
        this.perMinute = perMinute;
    }

    synchronized void perMinute(int perMinute) {
        this.perMinute = perMinute;
    }

    /** Millisecondes avant le prochain depot permis. */
    synchronized long waitMs(long now, boolean later) {
        while (!sent.isEmpty() && now - sent.peekFirst() >= WINDOW_MS) {
            sent.removeFirst();
        }
        long wait = Math.max(0, pausedUntil - now);
        if (!sent.isEmpty()) {
            wait = Math.max(wait, sent.peekLast() + GAP_MS - now);
        }
        int allowed = later ? Math.max(1, perMinute / 2) : perMinute;
        if (sent.size() >= allowed) {
            // Le plus ancien des « allowed » derniers doit sortir de la minute
            int index = sent.size() - allowed;
            long oldest = 0;
            for (long at : sent) {
                if (index-- == 0) {
                    oldest = at;
                    break;
                }
            }
            wait = Math.max(wait, oldest + WINDOW_MS - now);
        }
        return wait;
    }

    synchronized void sent(long now) {
        sent.addLast(now);
    }

    /** Depots encore permis dans la minute. */
    synchronized int left(long now) {
        int used = 0;
        for (long at : sent) {
            if (now - at < WINDOW_MS) {
                used++;
            }
        }
        return pausedUntil > now ? 0 : Math.max(0, perMinute - used);
    }

    synchronized void accepted() {
        refusals = 0;
    }

    /**
     * Refus du service : pause, doublee a chaque refus de suite.
     *
     * @param retryAfterMs pause demandee par le service, 0 s'il n'en donne pas
     * @return la pause
     */
    synchronized long refused(long now, long retryAfterMs) {
        long pause = Math.max(retryAfterMs, Math.min(PAUSE_MAX_MS, PAUSE_MS << Math.min(refusals, 8)));
        refusals++;
        pausedUntil = now + pause;
        return pause;
    }

    /** Plus rien avant cette duree (compte suspendu). */
    synchronized void pause(long now, long ms) {
        pausedUntil = now + ms;
    }

    synchronized long pausedFor(long now) {
        return Math.max(0, pausedUntil - now);
    }
}
