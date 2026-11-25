package org.Cry_Legende.sHSkins.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPaceTest {

    /** Depots faits pendant ce temps, en suivant les attentes a la lettre. */
    private static int sentIn(UploadPace pace, long from, long to, boolean later) {
        int sent = 0;
        for (long now = from; now < to; now += 10) {
            if (pace.waitMs(now, later) == 0) {
                pace.sent(now);
                sent++;
            }
        }
        return sent;
    }

    @Test
    void jamaisPlusQueLaLimiteParMinuteGlissante() {
        UploadPace pace = new UploadPace(16);
        assertEquals(16, sentIn(pace, 0, 60_000, false));
        UploadPace other = new UploadPace(16);
        List<Long> at = new ArrayList<>();
        for (long now = 0; now < 600_000; now += 10) {
            if (other.waitMs(now, false) == 0) {
                other.sent(now);
                at.add(now);
            }
        }
        for (int i = 0; i + 16 < at.size(); i++) {
            assertTrue(at.get(i + 16) - at.get(i) >= 60_000, "16 depots en " + (at.get(i + 16) - at.get(i)) + " ms");
        }
        assertEquals(160, at.size());
    }

    @Test
    void unSkinUrgentATouteSaPlace() {
        UploadPace pace = new UploadPace(16);
        assertEquals(8, sentIn(pace, 0, 60_000, true));
        // Meme apres une minute de depots sans urgence, il en reste huit tout de suite
        UploadPace busy = new UploadPace(16);
        sentIn(busy, 0, 30_000, true);
        assertEquals(8, sentIn(busy, 30_000, 45_000, false));
    }

    @Test
    void unRefusFaitUnePauseDePlusEnPlusLongue() {
        UploadPace pace = new UploadPace(16);
        assertEquals(120_000, pace.refused(0, 0));
        assertEquals(120_000, pace.waitMs(0, false));
        assertEquals(0, pace.waitMs(120_000, false));
        assertEquals(240_000, pace.refused(120_000, 0));
        assertEquals(900_000, pace.refused(400_000, 900_000));
        pace.accepted();
        assertEquals(120_000, pace.refused(2_000_000, 0));
        assertEquals(0, pace.left(2_000_001));
    }
}
