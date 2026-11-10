package com.scenix.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Offline coverage for the phone-pairing URL and fallback text. */
public final class PhonePairingTest {

    @Test
    public void standaloneEncodesTvLanIpOn8787() {
        assertEquals("http://192.168.0.42:8787",
            PhonePairing.pairingUrl(true, "http://hub:8788", "192.168.0.42", 8787));
    }

    @Test
    public void hubModeEncodesHubOrigin() {
        assertEquals("http://192.168.0.177:8791",
            PhonePairing.pairingUrl(false, "http://192.168.0.177:8791/", "192.168.0.42", 8787));
    }

    @Test
    public void hubOriginIsNormalised() {
        assertEquals("http://hub:8788",
            PhonePairing.normaliseOrigin(" hub:8788/ "));
        assertEquals("https://hub.example:8788",
            PhonePairing.normaliseOrigin("https://hub.example:8788/"));
        assertNull(PhonePairing.normaliseOrigin(""));
        assertNull(PhonePairing.normaliseOrigin(null));
    }

    @Test
    public void standaloneWithoutLanIpHasNoUrl() {
        assertNull(PhonePairing.pairingUrl(true, "http://hub:8788", null, 8787));
        assertNull(PhonePairing.pairingUrl(true, "http://hub:8788", "  ", 8787));
    }

    @Test
    public void fallbackTextIsTheQrUrl() {
        String url = PhonePairing.pairingUrl(false, "http://192.168.0.177:8791", null, 8787);
        assertNotNull(url);
        assertEquals("http://192.168.0.177:8791", PhonePairing.fallbackText(url));
    }

    /**
     * The row says "open Settings", so the code it prints has to arrive at the web UI's Settings
     * screen and not at the home board. The route lives in the fragment, which is why this is a
     * client-side deep link rather than a path the server must serve.
     */
    @Test
    public void qrLinkCarriesTheSettingsRoute() {
        assertEquals("http://192.168.0.24:8788/#/settings",
            PhonePairing.settingsUrl("http://192.168.0.24:8788"));
        assertEquals("http://192.168.0.42:8787/#/settings",
            PhonePairing.settingsUrl(PhonePairing.standaloneUrl("192.168.0.42", 8787)));
        // Idempotent: a link that already chose a screen is left alone.
        assertEquals("http://hub:8788/#/sources",
            PhonePairing.settingsUrl("http://hub:8788/#/sources"));
        assertNull(PhonePairing.settingsUrl(null));
        assertNull(PhonePairing.settingsUrl("  "));
    }
}
