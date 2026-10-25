#!/usr/bin/env python3
"""Generate app/src/main/assets/fixtures/{home,profiles,manifest}.json

The fixture is the offline fallback (hub unreachable -> bundled rows), so its row ids mirror the live
hub: continue, top10, justadded, newreleases, live, punjabiclassics, kids_yt, kids_school.

It deliberately includes the awkward data the hub really sends: `live.now` null, channels with no
match/maturity/backdrop, a row with `max`, and torrent sources that are listed but not playable.
Play URLs are public test streams, so the player is demoable with the hub down.

    python3 tools/gen_fixtures.py
"""
import json
import os

OUT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "src", "main", "assets", "fixtures"))

MP4 = "https://storage.googleapis.com/gtv-videos-bucket/sample/%s.mp4"
HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
NOW = 1761400000
DAY = 86400


def art(slug, w, h):
    return "https://picsum.photos/seed/%s/%d/%d" % (slug, w, h)


def item(ident, title, type="movie", year=0, slug=None, genres=None, langs=None,
         match=0, maturity="", durationMs=0, addedAt=0, releasedAt=0, inCinemas=False,
         live=None, progress=None, sources=None, backdrop=True, logo=None, kids=False):
    slug = slug or ident.split(":")[-1]
    d = {
        "identity": ident,
        "type": type,
        "title": title,
        "year": year,
        "poster": art(slug, 300, 450),
        "genres": genres or [],
        "languages": langs or ["pa"],
        "sources": sources or [],
    }
    if backdrop:
        d["backdrop"] = art(slug + "-wide", 1280, 720)
    if logo:
        d["logo"] = logo
    if match:
        d["match"] = match
    if maturity:
        d["maturity"] = maturity
    if durationMs:
        d["durationMs"] = durationMs
    if addedAt:
        d["addedAt"] = addedAt
    if releasedAt:
        d["releasedAt"] = releasedAt
    if inCinemas:
        d["inCinemas"] = True
    if kids:
        d["kids"] = True
    if live is not None:
        d["live"] = live
    if progress is not None:
        d["progress"] = progress
    return d


def src(provider, label, kind, quality="", sizeBytes=0, risk=""):
    d = {"provider": provider, "label": label, "kind": kind}
    if quality:
        d["quality"] = quality
    if sizeBytes:
        d["sizeBytes"] = sizeBytes
    if risk:
        d["risk"] = risk
    return d


def movie(ident, title, year, **kw):
    """A Punjabi feature: the shape the hub sends for most of the catalogue."""
    kw.setdefault("genres", ["Punjabi", "Comedy"])
    kw.setdefault("langs", ["pa", "en"])
    kw.setdefault("match", 82)
    kw.setdefault("maturity", "U/A 13+")
    kw.setdefault("durationMs", 7200000)
    kw.setdefault("addedAt", NOW - 30 * DAY)
    return item(ident, title, type="movie", year=year, **kw)


top10 = [
    movie("imdb:tt10958128", "Carri Onda Taaing De", 2019, match=91, slug="carri"),
    movie("imdb:tt8465434", "Golak Bugni Bank Te Batua", 2018, match=88, slug="golak",
          genres=["Punjabi", "Drama", "Crime"]),
    movie("imdb:tt7174262", "Lahoriye", 2017, match=86, slug="lahoriye",
          genres=["Punjabi", "Romance"]),
    movie("imdb:tt6107630", "Bambukat", 2016, match=84, slug="bambukat",
          genres=["Punjabi", "Dance"]),
    movie("imdb:tt6025394", "Nikka Zaildar", 2016, match=83, slug="nikka1",
          genres=["Punjabi", "Comedy", "Romance"]),
    movie("imdb:tt7325770", "Nikka Zaildar 2", 2017, match=80, slug="nikka2"),
    movie("imdb:tt3911278", "Punjab 1984", 2014, match=79, slug="p1984", maturity="U/A 16+",
          genres=["Punjabi", "History", "Drama"]),
    movie("imdb:tt4791376", "Sardaar Ji", 2015, match=76, slug="sardaar1",
          genres=["Punjabi", "Comedy", "Fantasy"]),
    movie("imdb:tt9516590", "Sardaar Ji 2", 2019, match=74, slug="sardaar2"),
    movie("imdb:tt7728804", "Manjeeta", 2017, match=72, slug="manjeeta",
          genres=["Punjabi", "Biography", "Sports"]),
]

continue_ = [
    movie("imdb:tt10958128", "Carri Onda Taaing De", 2019, match=91, slug="carri",
          progress={"positionMs": 3120000, "watchedMs": 3120000},
          sources=[src("adaptive", "Adaptive \u2014 1080p", "http", "1080p", 2400000000, "direct"),
                   src("youtube", "YouTube", "yt", "1080p", risk="signed link")]),
    item("norm:justice-ek-haqeeqat-2020", "Justice \u2014 Ek Haqeeqat", type="series", year=2020,
         slug="justice", genres=["Punjabi", "Crime", "Series"], match=78, maturity="U/A 16+",
         durationMs=2520000, addedAt=NOW - 9 * DAY,
         progress={"positionMs": 940000, "watchedMs": 940000},
         sources=[src("adaptive", "Adaptive", "hls", "up to 1080p", risk="adaptive"),
                  src("torrent", "Torrent \u2014 Season 1", "torrent", "1080p", 18000000000, "torrent")]),
    movie("norm:postto-2023", "Posto", 2023, match=69, slug="posto", durationMs=6900000,
          addedAt=NOW - 5 * DAY,
          progress={"positionMs": 480000, "watchedMs": 480000},
          sources=[src("adaptive", "Adaptive \u2014 720p", "http", "720p", 1200000000, "direct")]),
    movie("norm:chal-pindi-lau-de-2023", "Chal Pindi Lau De", 2023, match=71, slug="chalpindi",
          genres=["Punjabi", "Comedy", "Road"], addedAt=NOW - 2 * DAY,
          progress={"positionMs": 1180000, "watchedMs": 1180000},
          sources=[src("youtube", "YouTube", "yt", "1080p", risk="signed link"),
                   src("adaptive", "Adaptive \u2014 1080p", "http", "1080p", 2600000000, "direct")]),
]

justadded = [
    movie("norm:yaar-jatt-de-2024", "Yaar Jatt De", 2024, match=88, slug="yaarjatt",
          addedAt=NOW - 1 * DAY,
          sources=[src("adaptive", "Adaptive", "hls", "up to 1080p", risk="adaptive")]),
    movie("norm:mastaan-2022", "Mastaan", 2022, match=81, slug="mastaan", addedAt=NOW - 3 * DAY),
    movie("norm:bakala-2023", "Bakala", 2023, match=77, slug="bakala", addedAt=NOW - 4 * DAY,
          genres=["Punjabi", "Drama"]),
    movie("norm:chakvi-da-police-2022", "Chakvi Da Police", 2022, match=73, slug="chakvi",
          addedAt=NOW - 6 * DAY, genres=["Punjabi", "Crime"]),
    movie("imdb:tt10958128", "Carri Onda Taaing De", 2019, match=91, slug="carri",
          addedAt=NOW - 7 * DAY),
    movie("norm:jeeto-pakistan-special-2021", "Jeeto Pakistan Special", 2021, match=66,
          slug="jeeto", addedAt=NOW - 8 * DAY, genres=["Punjabi", "Music"]),
]

newreleases = [
    movie("norm:yaar-jatt-de-2024", "Yaar Jatt De", 2024, match=88, slug="yaarjatt",
          addedAt=NOW - 1 * DAY, releasedAt=NOW - 40 * DAY, inCinemas=True),
    movie("norm:sarpanch-2025", "Sarpanch", 2025, match=85, slug="sarpanch",
          addedAt=NOW - 2 * DAY, releasedAt=NOW - 20 * DAY, inCinemas=True),
    movie("norm:verry-ta-verry-2024", "Verry Ta Verry", 2024, match=79, slug="verry",
          addedAt=NOW - 12 * DAY, releasedAt=NOW - 90 * DAY),
    movie("norm:heer-ranjha-2024", "Heer Ranjha", 2024, match=78, slug="heer24",
          genres=["Punjabi", "Romance", "Tragedy"], addedAt=NOW - 14 * DAY,
          releasedAt=NOW - 120 * DAY),
    movie("imdb:tt8465434", "Golak Bugni Bank Te Batua", 2018, match=88, slug="golak",
          addedAt=NOW - 60 * DAY, releasedAt=NOW - 2400 * DAY),
    movie("imdb:tt3911278", "Punjab 1984", 2014, match=79, slug="p1984", maturity="U/A 16+",
          addedAt=NOW - 90 * DAY, releasedAt=NOW - 4000 * DAY),
]

classics = [
    movie("imdb:tt4163654", "Angrej", 2015, match=90, slug="angrej", addedAt=NOW - 200 * DAY,
          genres=["Punjabi", "Classic", "Romance"]),
    movie("norm:payal-ki-jhalar-1996", "Payal Ki Jhalar", 1996, match=74, slug="payal",
          addedAt=NOW - 210 * DAY, genres=["Punjabi", "Classic"]),
    movie("norm:nawab-sharif-1979", "Nawab Sharif", 1979, match=68, slug="nawab", maturity="U/A",
          addedAt=NOW - 220 * DAY, genres=["Punjabi", "Classic"]),
    movie("norm:heer-ranjha-1970", "Heer Ranjha", 1970, match=66, slug="heer70",
          addedAt=NOW - 230 * DAY, genres=["Punjabi", "Classic"]),
    movie("norm:yar-da-dil-1968-archive", "Yaar Da Dil (archive print)", 1968, match=61,
          slug="yar1968", genres=["Punjabi", "Classic"], maturity="U/A", addedAt=NOW - 240 * DAY,
          sources=[src("torrent", "Torrent \u2014 2160p archive scan", "torrent", "2160p",
                       41000000000, "unverified file")]),
    movie("imdb:tt3911278", "Punjab 1984", 2014, match=79, slug="p1984", maturity="U/A 16+",
          addedAt=NOW - 240 * DAY),
    movie("norm:mukhma-1990", "Mukhma", 1990, match=63, slug="mukhma", addedAt=NOW - 250 * DAY,
          genres=["Punjabi", "Classic"]),
]

channels = [
    item("norm:chardikla-time-tv-live", "Chardikla Time TV", type="channel", slug="chardikla",
         backdrop=False, logo=art("chardikla-logo", 200, 80),
         live={"now": "Prime Time Punjabi News", "quality": "1080i"},
         sources=[src("live", "Live", "hls", "1080i", risk="live")]),
    item("norm:9x-tashan-live", "9X Tashan", type="channel", slug="9xtashan", backdrop=False,
         sources=[src("live", "Live", "hls", "576i", risk="live")]),
    item("norm:ptv-home-live", "PTV Home", type="channel", slug="ptvhome", backdrop=False,
         live={"now": None, "quality": "576i"},
         sources=[src("live", "Live", "hls", "576i", risk="live")]),
    item("norm:ptv-sports-live", "PTV Sports", type="channel", slug="ptvsports", backdrop=False,
         live={"now": "Kabaddi: Punjab vs Services", "quality": "720p"},
         sources=[src("live", "Live", "hls", "720p", risk="live")]),
    item("norm:samaa-tv-live", "SAMAA TV", type="channel", slug="samaa", backdrop=False,
         live={"now": "Aaj Kamran Khan Ke Naam", "quality": "576i"},
         sources=[src("adaptive", "Adaptive", "http", "576i", risk="direct")]),
    item("norm:dil-tv-live", "Dil TV", type="channel", slug="diltv", backdrop=False,
         live={"now": "", "quality": ""},
         sources=[src("live", "Live", "hls", "576i", risk="live")]),
]

kids_yt = [
    item("norm:kids-yt-masha", "Masha and the Bear", type="video", year=2019, slug="masha",
         genres=["Kids", "Animation"], langs=["hi", "en"], match=95, maturity="U",
         durationMs=360000, addedAt=NOW - 2 * DAY, kids=True,
         sources=[src("youtube", "YouTube", "yt", "720p", risk="signed link")]),
    item("norm:kids-yt-chhota-bheem", "Chhota Bheem", type="video", year=2020, slug="bheem",
         genres=["Kids", "Animation"], langs=["hi"], match=90, maturity="U",
         durationMs=720000, addedAt=NOW - 3 * DAY, kids=True,
         sources=[src("adaptive", "Adaptive", "hls", "720p", risk="adaptive")]),
    item("norm:kids-yt-motopat", "Motu Patlu", type="video", year=2021, slug="motopat",
         genres=["Kids", "Comedy"], langs=["hi"], match=84, maturity="U",
         durationMs=600000, addedAt=NOW - 4 * DAY, kids=True,
         sources=[src("youtube", "YouTube", "yt", "480p", risk="signed link")]),
    item("norm:kids-yt-giddha", "Punjabi Giddha for Kids", type="video", year=2022, slug="giddha",
         genres=["Kids", "Music", "Punjabi"], langs=["pa"], match=80, maturity="U",
         durationMs=300000, addedAt=NOW - 5 * DAY, kids=True,
         sources=[src("adaptive", "Adaptive", "http", "720p", risk="direct")]),
]

kids_school = [
    item("norm:kids-school-varnamala", "Punjabi Varnmala \u2014 Letters", type="video", year=2021,
         slug="varnamala", genres=["Kids", "Education", "Punjabi"], langs=["pa"], match=88,
         maturity="U", durationMs=900000, addedAt=NOW - 10 * DAY, kids=True,
         sources=[src("adaptive", "Adaptive", "http", "720p", risk="direct")]),
    item("norm:kids-school-ganit", "Ganit \u2014 Counting to 100", type="video", year=2021,
         slug="ganit", genres=["Kids", "Education"], langs=["hi", "pa"], match=82, maturity="U",
         durationMs=1200000, addedAt=NOW - 11 * DAY, kids=True,
         sources=[src("adaptive", "Adaptive", "hls", "720p", risk="adaptive")]),
    item("norm:kids-school-faal", "Faal Fakriyaan \u2014 Science", type="video", year=2022,
         slug="faal", genres=["Kids", "Education"], langs=["pa", "en"], match=79, maturity="U",
         durationMs=1080000, addedAt=NOW - 12 * DAY, kids=True,
         sources=[src("youtube", "YouTube", "yt", "720p", risk="signed link")]),
]

hero = [
    movie("norm:yaar-jatt-de-2024", "Yaar Jatt De", 2024, match=94, slug="yaarjatt",
          genres=["Punjabi", "Comedy", "Family"], durationMs=8400000,
          addedAt=NOW - 1 * DAY, releasedAt=NOW - 40 * DAY, inCinemas=True,
          sources=[src("adaptive", "Adaptive", "hls", "up to 1080p", risk="adaptive"),
                   src("adaptive", "Adaptive \u2014 1080p", "http", "1080p", 2800000000, "direct"),
                   src("torrent", "Torrent \u2014 4K remux", "torrent", "2160p", 34000000000, "torrent")]),
]

rows = [
    {"id": "hero", "title": "", "card": "hero", "scope": "all", "order": 0, "items": hero},
    {"id": "continue", "title": "Continue Watching", "card": "continue", "scope": "all", "order": 1,
     "items": continue_},
    {"id": "top10", "title": "Top 10 in India Today", "card": "poster", "scope": "all", "order": 2,
     "max": 10, "items": top10},
    {"id": "justadded", "title": "Recently Added", "card": "poster", "scope": "all", "order": 3,
     "items": justadded},
    {"id": "newreleases", "title": "New & Popular", "card": "poster", "scope": "all", "order": 4,
     "items": newreleases},
    {"id": "live", "title": "Live TV", "card": "channel", "scope": "all", "order": 5, "items": channels},
    {"id": "punjabiclassics", "title": "Punjabi Classics", "card": "poster", "scope": "adult",
     "order": 6, "items": classics},
    {"id": "kids_yt", "title": "Kids \u00B7 Cartoons", "card": "poster", "scope": "kids", "order": 7,
     "items": kids_yt},
    {"id": "kids_school", "title": "Kids \u00B7 School Time", "card": "poster", "scope": "kids",
     "order": 8, "items": kids_school},
]

# identity -> what POST /app/v1/play answers.
PLAY = {
    "norm:yaar-jatt-de-2024": {"url": MP4 % "ElephantsDream", "kind": "torrent",
                                "mode": "gateway",
                                "why": ["the hub fetched the torrent copy",
                                        "served on as ordinary seekable HTTP"]},
    "norm:yar-da-dil-1968-archive": {"url": "magnet:?xt=urn:btih:0f1e2d3c4b5a69788796a5b4c3d2e1f0a1b2c3d4&dn=Yaar+Da+Dil+1968+2160p",
                                     "kind": "torrent", "mode": "direct"},
    "imdb:tt10958128": {"url": MP4 % "BigBuckBunny", "kind": "http"},
    "imdb:tt8465434": {"url": MP4 % "ForBiggerBlazes", "kind": "http"},
    "imdb:tt7174262": {"url": MP4 % "ForBiggerEscapes", "kind": "http"},
    "imdb:tt6107630": {"url": MP4 % "ForBiggerFun", "kind": "http"},
    "imdb:tt6025394": {"url": MP4 % "ForBiggerJoyrides", "kind": "http"},
    "imdb:tt7325770": {"url": MP4 % "ForBiggerMeltdowns", "kind": "http"},
    "imdb:tt3911278": {"url": MP4 % "TearsOfSteel", "kind": "http"},
    "imdb:tt4791376": {"url": MP4 % "Sintel", "kind": "http"},
    "imdb:tt9516590": {"url": MP4 % "VolkswagenGTIReview", "kind": "http"},
    "imdb:tt7728804": {"url": MP4 % "WeAreGoingOnBullrun", "kind": "http"},
    "imdb:tt4163654": {"url": MP4 % "SubaruOutbackOnStreetAndDirt", "kind": "http"},
    "norm:justice-ek-haqeeqat-2020": {"url": HLS, "kind": "hls"},
    "norm:postto-2023": {"url": MP4 % "WhatWeDidOnOurVacation", "kind": "http"},
    "norm:chal-pindi-lau-de-2023": {"url": MP4 % "BigBuckBunny", "kind": "yt"},
    "norm:chardikla-time-tv-live": {"url": HLS, "kind": "hls"},
    "norm:9x-tashan-live": {"url": HLS, "kind": "hls"},
    "norm:ptv-home-live": {"url": HLS, "kind": "hls"},
    "norm:ptv-sports-live": {"url": HLS, "kind": "hls"},
    "norm:samaa-tv-live": {"url": MP4 % "ForBiggerBlazes", "kind": "http"},
    "norm:dil-tv-live": {"url": HLS, "kind": "hls"},
    "norm:kids-yt-masha": {"url": MP4 % "ForBiggerFun", "kind": "yt"},
    "norm:kids-yt-chhota-bheem": {"url": HLS, "kind": "hls"},
    "norm:kids-yt-motopat": {"url": MP4 % "ForBiggerJoyrides", "kind": "yt"},
    "norm:kids-yt-giddha": {"url": MP4 % "ForBiggerMeltdowns", "kind": "http"},
    "norm:kids-school-varnamala": {"url": MP4 % "ForBiggerEscapes", "kind": "http"},
    "norm:kids-school-ganit": {"url": HLS, "kind": "hls"},
    "norm:kids-school-faal": {"url": MP4 % "ForBiggerFun", "kind": "yt"},
}

SAMPLES = ["BigBuckBunny", "ElephantsDream", "Sintel"]
_i = 0
for r in rows:
    for it in r["items"]:
        if it["identity"] not in PLAY:
            PLAY[it["identity"]] = {"url": MP4 % SAMPLES[_i % 3], "kind": "http"}
            _i += 1

# ---------------------------------------------------------------- source ranking
# The hub ranks a title's sources before the TV ever sees them: best first, each with a
# `score` and `why[]` in plain words. The fixture does the same thing so the offline demo
# exercises failover, the Source list and the "why this one" line honestly.
WHY = {
    "adaptive": ["adaptive ladder \u2014 walks down by itself on a bad Wi-Fi",
                 "matches this profile's languages"],
    "http": ["single seekable file", "no ladder to fall back on"],
    "youtube": ["official channel upload", "signed link expires in about 4 h"],
    "torrent": ["highest quality copy", "needs a stream engine on this TV"],
    "live": ["on air now", "quality follows the feed"],
}


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return "%d %s" % (n, unit)
        n //= 1024
    return "%d TB" % n


def rank_sources(it, torrent_first=False):
    """Give every source a score and the hub's reason, best first."""
    srcs = it.get("sources") or []
    if not srcs:
        return
    order = list(srcs)
    if torrent_first:
        # One title where the best copy really is a torrent, so the app has to show the
        # hand-off instead of quietly skipping it.
        order = [x for x in order if x.get("kind") == "torrent"] + \
                [x for x in order if x.get("kind") != "torrent"]
    for i, s in enumerate(order):
        s["score"] = round(9.9 - i * 1.4, 2)
        why = list(WHY.get(s.get("provider", ""), []))
        if s.get("quality"):
            why.insert(0, "%s copy" % s["quality"])
        if s.get("sizeBytes"):
            why.append("%s file" % human(s["sizeBytes"]))
        if i == 0:
            why.append("ranked first by the hub")
        s["why"] = why


TORRENT_FIRST = {"norm:yaar-jatt-de-2024"}
for r in rows:
    for it in r["items"]:
        rank_sources(it, torrent_first=it["identity"] in TORRENT_FIRST)

# ---------------------------------------------------------------- subtitles
# Served from the fixture's own assets so the subtitle path is exercised with no network.
# The hub would hand back http URLs; the app treats both the same.
SUB_LANGS = {
    "eng": ("English", ["Hello.", "Where are you going?", "That is the last bus.", "Come on, let us go."]),
    "hin": ("Hindi", ["नमस्ते।", "आप कहाँ जा रहे हैं?", "यह आख़िरी बस है।", "चलिए, चलते हैं।"]),
    "pan": ("Punjabi", ["ਸਤ ਸ੍ਰੀ ਅਕਾਲ।", "ਤੁਸੀਂ ਕਿੱਥੇ ਜਾ ਰਹੇ ਹੋ?", "ਇਹ ਆਖ਼ਰੀ ਬੱਸ ਹੈ।", "ਚਲੋ, ਚਲੀਏ।"]),
}


def tt(ms):
    h, rem = divmod(ms, 3600000)
    m, rem = divmod(rem, 60000)
    sec, mil = divmod(rem, 1000)
    return "%02d:%02d:%02d.%03d" % (h, m, sec, mil)


def write_vtt(path, lines):
    out = ["WEBVTT", ""]
    t = 1500
    for ln in lines:
        out.append("%s --> %s" % (tt(t), tt(t + 3500)))
        out.append(ln)
        out.append("")
        t += 5200
    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")


sub_dir = os.path.join(OUT, "subs")
os.makedirs(sub_dir, exist_ok=True)
for code, (_name, lines) in SUB_LANGS.items():
    write_vtt(os.path.join(sub_dir, code + ".vtt"), lines)


def subs_for(ident, codes):
    return [
        {
            "id": "%s-%s" % (ident.split(":")[-1], code),
            "addon": "Built-in subtitle pack",
            "lang": code,
            "label": SUB_LANGS[code][0],
            "url": "file:///android_asset/fixtures/subs/%s.vtt" % code,
        }
        for code in codes
    ]


SUBS = {
    "imdb:tt10958128": subs_for("imdb:tt10958128", ["eng", "hin", "pan"]),
    "imdb:tt8465434": subs_for("imdb:tt8465434", ["eng", "hin"]),
    "imdb:tt7174262": subs_for("imdb:tt7174262", ["eng"]),
}

prefs = {
    "prefs": {
        "profile": "raj",
        "audioLanguages": ["hin", "eng"],
        "subtitleLanguages": ["eng"],
        "quality": "1080",
        "maxFileSizeBytes": 2000000000,
        "maxBitrateBps": 6000000,
        "why": ["prefers Hindi, then English audio", "caps at 1080p",
                "refuses files over 2 GB", "refuses streams over 6.0 Mbps"],
    }
}

addons = [
    {"id": "library", "name": "Titles library", "origin": "native", "kinds": ["movie", "series"],
     "count": 1420, "lastError": ""},
    {"id": "live", "name": "Live TV", "origin": "native", "kinds": ["channel"], "count": 61,
     "lastError": ""},
    {"id": "kids", "name": "Kids", "origin": "native", "kinds": ["video"], "count": 310,
     "lastError": ""},
    {"id": "user-1", "name": "Punjabi music videos", "origin": "user", "kinds": ["video"],
     "count": 88, "lastError": ""},
    {"id": "user-2", "name": "Sports replays", "origin": "user", "kinds": ["movie", "video"],
     "count": 0, "lastError": "timed out fetching index"},
]

profiles = {
    "profiles": [
        {"id": "raj", "name": "Raj", "isKids": False, "hasPin": False,
         "audioLanguages": ["hin", "eng"], "subtitleLanguages": ["eng"]},
        {"id": "nagraj", "name": "Nagraj", "isKids": False, "hasPin": False,
         "languages": ["pa", "en"], "audioLanguages": ["pa", "hin", "en"],
         "subtitleLanguages": ["eng", "pan"]},
        {"id": "meera", "name": "Meera", "isKids": False, "hasPin": True,
         "audioLanguages": ["pa", "en"], "subtitleLanguages": ["en"]},
        {"id": "aarav", "name": "Aarav", "isKids": True, "hasPin": True,
         "audioLanguages": ["en", "hin"], "subtitleLanguages": []},
    ]
}

manifest = {
    "manifest": {
        "name": "GharTV (bundled fixtures)",
        "version": "0.1-p1",
        "theme": "ghartv-dark",
        "rows": ["hero", "continue", "top10", "justadded", "newreleases", "live",
                 "punjabiclassics", "kids_yt", "kids_school"],
        "cards": ["poster", "continue", "channel", "hero"],
        "rules": ["dedupe", "hiderows", "kids", "riskorder"],
        "providers": ["adaptive", "live", "youtube", "torrent"],
        "subs": ["adaptive", "torrent"],
        "addons": addons,
        "_note": "torrent sources are listed but not playable on this TV: no engine on 2 GB / armeabi-v7a",
    }
}

os.makedirs(OUT, exist_ok=True)
HOME_READ = [
    "Offline fallback catalogue, same shapes as GET /app/v1/home.",
    "The live hub is the real source; this is what the TV shows when the hub is not answering.",
    "play: identity -> the answer POST /app/v1/play would give.",
    "subs: identity -> the answer GET /app/v1/subs/<identity> would give (files under fixtures/subs).",
    "prefs.json (same folder) is what GET /app/v1/prefs would answer.",
]
for name, obj in (("home.json", {"_read": HOME_READ, "rows": rows, "play": PLAY, "subs": SUBS}),
                  ("profiles.json", profiles),
                  ("manifest.json", manifest),
                  ("prefs.json", prefs)):
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
        f.write("\n")

print("wrote:", sorted(os.listdir(OUT)))
print("items:", sum(len(r["items"]) for r in rows), "rows:", len(rows), "play:", len(PLAY))
