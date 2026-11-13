package com.scenix.tv.core;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Offline coverage for the origin-selection boundary before HubWatch starts network work. */
public final class HubCandidatePolicyTest {

    @Test
    public void selectedHubIsTheOnlyProbeCandidate() {
        assertEquals(
            Collections.singletonList("http://selected.example:8788"),
            HubCandidatePolicy.candidates(
                " http://selected.example:8788/ ",
                "http://previous.example:8788",
                Arrays.asList("http://lan-one.example:8788", "http://lan-two.example:8788"),
                "http://seed.example:8788"
            )
        );
    }

    @Test
    public void recoveryCandidatesAreAvailableBeforeSelection() {
        assertEquals(
            Arrays.asList(
                "http://last.example:8788",
                "http://lan-one.example:8788",
                "http://lan-two.example:8788",
                "http://seed.example:8788"
            ),
            HubCandidatePolicy.candidates(
                "",
                "http://last.example:8788/",
                Arrays.asList("http://lan-one.example:8788", "http://last.example:8788", "http://lan-two.example:8788/"),
                " http://seed.example:8788, http://lan-one.example:8788 "
            )
        );
    }

    @Test
    public void blankAndDuplicateRecoveryValuesAreIgnored() {
        assertEquals(
            Collections.singletonList("http://last.example:8788"),
            HubCandidatePolicy.candidates(
                null,
                " http://last.example:8788/ ",
                Arrays.asList("", "   ", "http://last.example:8788"),
                ", ,"
            )
        );
    }
}
