package com.scenix.tv.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Contract coverage for the boundary between the control API and media delivery. */
public final class PlaybackOriginPolicyTest {

    @Test
    public void fixtureLoopbackControlDoesNotSeedARemoteHlsFixture() {
        assertFalse(PlaybackOriginPolicy.usesLocalLadderSeed(
            "http://127.0.0.1:8788",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "hls",
            true
        ));
    }

    @Test
    public void standaloneLoopbackControlDoesNotSeedAThirdPartyHlsStream() {
        assertFalse(PlaybackOriginPolicy.usesLocalLadderSeed(
            "http://localhost:8788",
            "https://cdn.example.test/title/master.m3u8",
            "hls",
            true
        ));
    }

    @Test
    public void localSameOriginHlsLadderGetsTheLanSeed() {
        assertTrue(PlaybackOriginPolicy.usesLocalLadderSeed(
            "http://192.168.0.24:8788",
            "http://192.168.0.24:8788/app/v1/ladder/abc/master.m3u8",
            "hls",
            true
        ));
    }

    @Test
    public void directOrRemoteLaddersNeverGetTheLanSeed() {
        assertFalse(PlaybackOriginPolicy.usesLocalLadderSeed(
            "http://192.168.0.24:8788",
            "http://192.168.0.24:8788/app/v1/ladder/abc/master.m3u8",
            "hls",
            false
        ));
        assertFalse(PlaybackOriginPolicy.usesLocalLadderSeed(
            "https://hub.example.test",
            "https://hub.example.test/app/v1/ladder/abc/master.m3u8",
            "hls",
            true
        ));
    }
}
