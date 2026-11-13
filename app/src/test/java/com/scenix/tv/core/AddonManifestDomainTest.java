package com.scenix.tv.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/** Offline contract tests for standalone manifest parsing and escaped protocol request planning. */
public final class AddonManifestDomainTest {

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static AddonManifestDomain domain() {
        AddonCatalogSpec top = new AddonCatalogSpec(
            "movie", "top", "Top", Arrays.asList(
                new AddonExtraSpec("search", false, Collections.emptySet(), null),
                new AddonExtraSpec("skip", false, Collections.emptySet(), null)
            ), null
        );
        AddonCatalogSpec genre = new AddonCatalogSpec(
            "movie", "genre", "", Collections.singletonList(
                new AddonExtraSpec("genre", true, set("Action", "Drama"), null)
            ), null
        );
        AddonCatalogSpec community = new AddonCatalogSpec(
            "all", "community", "", Collections.emptyList(), null
        );
        return AddonManifestDomain.Companion.fromParts(
            Arrays.asList(
                new AddonResourceSpec("catalog", set("movie", "series"), set("tt")),
                new AddonResourceSpec("meta", set("movie", "series"), set("tt")),
                new AddonResourceSpec("stream", set("movie"), set("tt")),
                new AddonResourceSpec("subtitles", set("movie", "series"), set("tt")),
                new AddonResourceSpec("addon_catalog", set("movie", "series"), Collections.emptySet())
            ),
            Arrays.asList(top, genre),
            Collections.singletonList(community),
            new AddonConfigurationHints(true, true, true, true, false)
        );
    }

    @Test
    public void exposesTypedResourcesCatalogsAddonCatalogsAndConfigurationHints() {
        AddonManifestDomain value = domain();

        assertTrue(value.has("catalog"));
        assertTrue(value.has("addon_catalog"));
        assertEquals(2, value.getCatalogs().size());
        assertEquals(1, value.getAddonCatalogs().size());
        assertTrue(value.getConfiguration().getConfigurable());
        assertTrue(value.getConfiguration().getConfigurationRequired());
        assertTrue(value.getConfiguration().getNeedsSecureHandoff());
        assertTrue(value.getConfiguration().getAdult());
        assertTrue(value.getConfiguration().getPeerToPeer());
        assertFalse(value.getConfiguration().getEpgProvider());
        assertEquals(Collections.singletonList("top"),
            value.searchableCatalogs().stream().map(AddonCatalogSpec::getId).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    public void onlyPlansDeclaredCatalogControlsAndEscapesTheirWholePathSegment() {
        AddonManifestDomain value = domain();

        AddonRequest canonicalDefault = value.planCatalog("movie", "top", Collections.emptyMap());
        assertNotNull(canonicalDefault);
        assertEquals("catalog/movie/top.json", canonicalDefault.getRelativePath());
        AddonRequest legacyDefault = value.planLegacyCatalog("movie", "top", "default");
        assertNotNull(legacyDefault);
        assertEquals("catalog/movie/top/default.json", legacyDefault.getRelativePath());

        AddonRequest search = value.planLegacyCatalog("movie", "top", "search=The%20Thing");
        assertNotNull(search);
        assertEquals("catalog/movie/top/search=The%20Thing.json", search.getRelativePath());

        assertNull(value.planLegacyCatalog("movie", "genre", "default"));
        AddonRequest genre = value.planLegacyCatalog("movie", "genre", "genre=Action");
        assertNotNull(genre);
        assertEquals("catalog/movie/genre/genre=Action.json", genre.getRelativePath());
        assertNull(value.planLegacyCatalog("movie", "genre", "genre=Horror"));
        assertNull(value.planLegacyCatalog("movie", "top", "unlisted=anything"));
    }

    @Test
    public void itemAndDirectoryRequestsCannotInjectAnotherRoute() {
        AddonManifestDomain value = domain();

        AddonRequest meta = value.planMeta("movie", "tt1/../../private");
        assertNotNull(meta);
        assertEquals("meta/movie/tt1%2F..%2F..%2Fprivate.json", meta.getRelativePath());
        assertNull(value.planMeta("movie", "nm123"));

        AddonRequest directory = value.planAddonCatalog("all", "community", Collections.emptyMap());
        assertNotNull(directory);
        assertEquals("addon_catalog/all/community.json", directory.getRelativePath());
        assertNull(value.planAddonCatalog("all", "unknown", Collections.emptyMap()));

        AddonRequest subtitle = value.planSubtitles("series", "tt9", "en-US");
        assertNotNull(subtitle);
        assertEquals("subtitles/series/tt9/en-us.json", subtitle.getRelativePath());
        assertNull(value.planSubtitles("series", "tt9", "en/../../secret"));
    }

    @Test
    public void resourceTypeConstraintsRemainInForce() {
        AddonManifestDomain value = domain();
        assertNotNull(value.planStreams("movie", "tt5"));
        assertNull(value.planStreams("series", "tt5"));
        assertFalse(value.types("not-a-resource").size() > 0);
    }
}
