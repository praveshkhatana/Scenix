'use strict';
/*
 * providers/public-domain.js — a small, curated shelf of films that are in the public
 * domain in the United States, streamed straight from the Internet Archive.
 *
 * Why this file exists
 * --------------------
 * Scenix itself hosts no video. A fresh hub otherwise has nothing on its shelves until
 * the owner installs an add-on, which reads as a broken app on a new TV. These titles are
 * bundled the way Stremio bundles its legal defaults: the catalogue is a plain list in
 * this file, and each entry points at a direct MP4 the Internet Archive already serves.
 * Nothing is scraped, mirrored, proxied through a third party, or fetched from a personal
 * account. There is no torrent, no paid service and no DRM in this provider.
 *
 * Legal basis (curated, not scraped)
 * ----------------------------------
 * Every title below is public domain in the United States for one of two reasons:
 *   1. It was published before 1931 and its U.S. copyright term has expired
 *      (The General, Metropolis, Nosferatu, The Cabinet of Dr. Caligari, …).
 *   2. It was published in the U.S. between 1930 and 1963 and its copyright was not
 *      renewed, or it was released without the required notice
 *      (Night of the Living Dead, His Girl Friday, Charade, Detour, …).
 * The list was checked by hand against the Internet Archive's public-domain film
 * collections and the standard U.S. renewal records. It is deliberately short: a title
 * is better left out than shipped on a guess. Copyright is territorial, so this is a
 * U.S. statement; an owner elsewhere can switch the add-on off in Settings. A modern
 * restoration can add its own score or translated title cards, which are the restorer's
 * work rather than part of the film's public-domain status; the entries below point at
 * Archive prints of the film itself, not at a claim about those additions.
 *
 * Sources and art
 * ---------------
 * Each entry pins:
 *   · an Internet Archive identifier (`archive.org/details/<identifier>`),
 *   · one MP4 filename inside that item (the direct URL is built below),
 *   · the file's size in bytes, so the hub's own ranking can reason about bitrate,
 *   · archive.org's own item thumbnail as the poster.
 * The posters are URLs, not files in this repository: no third-party artwork is copied
 * into the source tree. If the Archive removes an item, that one card disappears and the
 * rest of the shelf keeps working; the hub never caches or re-hosts the video itself.
 *
 * Native contract
 * ---------------
 * catalogs() / catalog() / meta() / play() are the hub provider contract. `stream()` is
 * kept as an explicit alias for the community-protocol word, so a reader looking for
 * "stream(id)" finds the same answer. IDs are `pd:archive-<identifier>`; the `pd:`
 * namespace cannot collide with an IMDb, YouTube, live-TV or installed-addon id.
 *
 * This provider is a fresh-install default, not a migration: `freshInstallOnly` below
 * tells core/addons.js not to append it to an existing data/addons.json. An owner who
 * wants it can add the one native entry from addons.example.json; nobody's list is
 * rewritten behind their back.
 */

const PROVIDER_ID = 'public-domain';
const CATALOG_ID = 'public-domain';
const ID_PREFIX = 'pd:archive-';

/**
 * The curated catalogue, in the order the shelf shows it. Keep this list small and
 * factual: title, year, one original sentence, the archive identifier and the exact MP4
 * filename. No rating is invented, because an empty rating is more honest than a guess.
 */
const CATALOG = [
  {
    archive: 'night_of_the_living_dead_ipod',
    file: 'night_of_the_living_dead_512kb.mp4',
    sizeBytes: 415085259,
    title: 'Night of the Living Dead',
    year: 1968,
    runtimeMin: 95,
    genres: ['Horror', 'Thriller'],
    director: 'George A. Romero',
    cast: ['Duane Jones', 'Judith O\'Dea', 'Karl Hardman'],
    country: 'USA',
    overview: 'Strangers barricade themselves in a Pennsylvania farmhouse as the recently dead rise outside. George Romero\'s first feature turned a tiny budget into the template for the modern zombie film.',
  },
  {
    archive: 'HisGirlFriday-1940',
    file: 'CaryGrant-1940-HisGirlFriday.mp4',
    sizeBytes: 531285806,
    title: 'His Girl Friday',
    year: 1940,
    runtimeMin: 88,
    genres: ['Comedy', 'Romance'],
    director: 'Howard Hawks',
    cast: ['Cary Grant', 'Rosalind Russell', 'Ralph Bellamy'],
    country: 'USA',
    overview: 'A newspaper editor schemes to keep his ace reporter and ex-wife from remarrying, using a breaking story as the bait. The overlapping dialogue is still the fastest comedy in Hollywood.',
  },
  {
    archive: 'charade-1963-cary-grant-audrey-hepburn-walter-matthau-1080p-reup',
    file: 'Charade (1963) Cary Grant, Audrey Hepburn, Walter Matthau, James Coburn, George Kennedy; 1080p].mp4',
    sizeBytes: 1276998804,
    title: 'Charade',
    year: 1963,
    runtimeMin: 113,
    genres: ['Mystery', 'Romance', 'Thriller', 'Comedy'],
    director: 'Stanley Donen',
    cast: ['Cary Grant', 'Audrey Hepburn', 'Walter Matthau'],
    country: 'USA',
    overview: 'A young widow in Paris discovers that her murdered husband stole a fortune, and that several dangerous men believe she still has it. A light romantic thriller with a very dark centre.',
  },
  {
    archive: 'TheGeneral_201312',
    file: 'The-General-v2.mp4',
    sizeBytes: 708672423,
    title: 'The General',
    year: 1926,
    runtimeMin: 79,
    genres: ['Comedy', 'Action', 'War'],
    director: 'Buster Keaton',
    cast: ['Buster Keaton', 'Marion Mack'],
    country: 'USA',
    overview: 'A Southern railway engineer chases the Union spies who stole his locomotive and his sweetheart. Keaton performs the stunts for real, and the result is one of the great action comedies.',
  },
  {
    archive: 'metropolis_202511',
    file: 'Metropolis .mp4',
    sizeBytes: 1138281095,
    title: 'Metropolis',
    year: 1927,
    runtimeMin: 148,
    genres: ['Science Fiction', 'Drama'],
    director: 'Fritz Lang',
    cast: ['Alfred Abel', 'Brigitte Helm', 'Gustav Fröhlich'],
    country: 'Germany',
    overview: 'In a future city split between luxury above and machinery below, the son of the ruler falls for a worker\'s prophet. Fritz Lang\'s silent epic is the blueprint for nearly every screen dystopia since.',
  },
  {
    archive: 'plan-9-from-outer-space',
    file: 'plan-9-from-outer-space.mp4',
    sizeBytes: 454359304,
    title: 'Plan 9 from Outer Space',
    year: 1959,
    runtimeMin: 78,
    genres: ['Science Fiction', 'Horror'],
    director: 'Ed Wood',
    cast: ['Gregory Walcott', 'Mona McKinnon', 'Bela Lugosi'],
    country: 'USA',
    overview: 'Aliens resurrect the dead in a small California town and announce a plan to stop humanity from destroying the universe. Ed Wood\'s earnest, threadbare masterpiece is more fun than its reputation suggests.',
  },
  {
    archive: 'nosferatu-1922_202406',
    file: 'Nosferatu 1922.mp4',
    sizeBytes: 228683235,
    title: 'Nosferatu',
    year: 1922,
    runtimeMin: 93,
    genres: ['Horror', 'Fantasy'],
    director: 'F. W. Murnau',
    cast: ['Max Schreck', 'Greta Schröder'],
    country: 'Germany',
    overview: 'A real-estate clerk travels to a remote castle and unknowingly brings a vampire back to his town. Murnau\'s unlicensed Dracula is still the strangest and most shadowy of the early horror films.',
  },
  {
    archive: 'the-cabinet-of-dr-caligari-1920',
    file: 'The Cabinet of Dr Caligari (1920).mp4',
    sizeBytes: 167121474,
    title: 'The Cabinet of Dr. Caligari',
    year: 1920,
    runtimeMin: 77,
    genres: ['Horror', 'Mystery', 'Drama'],
    director: 'Robert Wiene',
    cast: ['Werner Krauss', 'Conrad Veidt'],
    country: 'Germany',
    overview: 'A hypnotist exhibits a sleepwalker who may be committing murders in a town of painted shadows. The tilted sets and unreliable narrator made this the founding text of psychological horror.',
  },
  {
    archive: 'the-phantom-of-the-opera-1925',
    file: 'The Phantom of the Opera 1925.mp4',
    sizeBytes: 210972369,
    title: 'The Phantom of the Opera',
    year: 1925,
    runtimeMin: 92,
    genres: ['Horror', 'Drama'],
    director: 'Rupert Julian',
    cast: ['Lon Chaney', 'Mary Philbin'],
    country: 'USA',
    overview: 'A masked figure living beneath the Paris Opera takes a young singer under his protection. Lon Chaney\'s self-designed face remains the image people mean when they say "the Phantom".',
  },
  {
    archive: 'sherlock-jr.-1924',
    file: 'Sherlock Jr.1924.mp4',
    sizeBytes: 270483938,
    title: 'Sherlock Jr.',
    year: 1924,
    runtimeMin: 45,
    genres: ['Comedy', 'Fantasy'],
    director: 'Buster Keaton',
    cast: ['Buster Keaton', 'Kathryn McGuire'],
    country: 'USA',
    overview: 'A daydreaming projectionist walks into the film he is showing and tries to solve a crime as a great detective. Keaton\'s short, perfect comedy is a love letter to the movies themselves.',
  },
  {
    archive: 'the-gold-rush-1925-tmdb962',
    file: 'The Gold Rush (1925).mp4',
    sizeBytes: 543893484,
    title: 'The Gold Rush',
    year: 1925,
    runtimeMin: 89,
    genres: ['Comedy', 'Adventure', 'Western'],
    director: 'Charlie Chaplin',
    cast: ['Charlie Chaplin', 'Mack Swain', 'Georgia Hale'],
    country: 'USA',
    overview: 'A lone prospector heads north for gold and finds hunger, a blizzard and an unlikely romance. The cabin teetering on a cliff and the dance of the rolls are among Chaplin\'s most durable images.',
  },
  {
    archive: 'silent-safety-last',
    file: 'Safety Last!.mp4',
    sizeBytes: 385048953,
    title: 'Safety Last!',
    year: 1923,
    runtimeMin: 74,
    genres: ['Comedy', 'Action'],
    director: 'Fred C. Newmeyer',
    cast: ['Harold Lloyd', 'Mildred Davis'],
    country: 'USA',
    overview: 'A small-town boy goes to the city, pretends to be a success, and ends up climbing a building for real. Harold Lloyd\'s clock-face stunt is still the reason people remember the film.',
  },
  {
    archive: 'TheGreatTrainRobbery_555',
    file: 'TheGreatTrainRobbery.mp4',
    sizeBytes: 240631332,
    title: 'The Great Train Robbery',
    year: 1903,
    runtimeMin: 12,
    genres: ['Western', 'Action'],
    director: 'Edwin S. Porter',
    cast: ['Justus D. Barnes', 'Broncho Billy Anderson'],
    country: 'USA',
    overview: 'Bandits hold up a train, then are hunted across the countryside. Twelve minutes long and more than a century old, it is one of the first films to tell a story with cuts instead of a stage.',
  },
  {
    archive: 'ATripToTheMoon20Fps',
    file: 'TripToTheMoon20Fps.mp4',
    sizeBytes: 154308170,
    title: 'A Trip to the Moon',
    year: 1902,
    runtimeMin: 11,
    genres: ['Science Fiction', 'Fantasy', 'Adventure'],
    director: 'Georges Méliès',
    cast: ['Georges Méliès', 'Bleuette Bernon'],
    country: 'France',
    overview: 'A band of astronomers is fired from a cannon to the Moon and meets its strange inhabitants. Georges Méliès turned stage illusion into cinema\'s first great special-effects spectacle.',
  },
  {
    archive: 'The_Little_Shop_of_Horrors_60',
    file: 'The_Little_Shop_of_Horrors_60.mp4',
    sizeBytes: 429536360,
    title: 'The Little Shop of Horrors',
    year: 1960,
    runtimeMin: 73,
    genres: ['Comedy', 'Horror'],
    director: 'Roger Corman',
    cast: ['Jonathan Haze', 'Jackie Joseph', 'Mel Welles'],
    country: 'USA',
    overview: 'A hapless florist\'s assistant grows a plant that demands human blood. Shot in a couple of days, Roger Corman\'s comedy is a scruffy, fast-talking cult favourite.',
  },
  {
    archive: 'detour-1945_202310',
    file: 'Detour (1945).mp4',
    sizeBytes: 429282962,
    title: 'Detour',
    year: 1945,
    runtimeMin: 69,
    genres: ['Crime', 'Drama', 'Thriller'],
    director: 'Edgar G. Ulmer',
    cast: ['Tom Neal', 'Ann Savage'],
    country: 'USA',
    overview: 'A nightclub pianist hitchhikes to Los Angeles and is pulled into a spiral of bad luck and worse decisions. Poverty-row filmmaking at its most fatalistic, with Ann Savage as one of noir\'s great predators.',
  },
  {
    archive: 'd.-o.-a.-1950',
    file: 'D.O.A. (1949).mp4',
    sizeBytes: 929763951,
    title: 'D.O.A.',
    year: 1949,
    runtimeMin: 83,
    genres: ['Crime', 'Mystery', 'Thriller'],
    director: 'Rudolph Maté',
    cast: ['Edmond O\'Brien', 'Pamela Britton'],
    country: 'USA',
    overview: 'An accountant learns he has been poisoned and has a day to find his own killer. The clock is the villain, and the hero spends the whole film running out of time.',
  },
  {
    archive: 'TheHitchHiker1953_201901',
    file: 'The Hitch-Hiker (1953).mp4',
    sizeBytes: 322599147,
    title: 'The Hitch-Hiker',
    year: 1953,
    runtimeMin: 71,
    genres: ['Thriller', 'Crime'],
    director: 'Ida Lupino',
    cast: ['Edmond O\'Brien', 'Frank Lovejoy', 'William Talman'],
    country: 'USA',
    overview: 'Two friends on a fishing trip pick up a hitchhiker who turns out to be a killer, and keeps them driving. Ida Lupino\'s taut thriller stays in the car and never lets the audience out.',
  },
  {
    archive: 'kansascityconfidencial',
    file: 'kansascityconfidencial.mp4',
    sizeBytes: 615919464,
    title: 'Kansas City Confidential',
    year: 1952,
    runtimeMin: 99,
    genres: ['Crime', 'Drama', 'Thriller'],
    director: 'Phil Karlson',
    cast: ['John Payne', 'Coleen Gray', 'Preston Foster'],
    country: 'USA',
    overview: 'An ex-con is framed for an armoured-car robbery and follows the real crew to Mexico to clear his name. A hard, sun-baked heist picture with a memorable masked gang.',
  },
  {
    archive: 'TheStranger_201511',
    file: 'The_Stranger.mp4',
    sizeBytes: 570096533,
    title: 'The Stranger',
    year: 1946,
    runtimeMin: 91,
    genres: ['Thriller', 'Crime', 'Drama'],
    director: 'Orson Welles',
    cast: ['Orson Welles', 'Edward G. Robinson', 'Loretta Young'],
    country: 'USA',
    overview: 'A small New England town hides a Nazi war criminal, and a war-crimes investigator is closing in. Welles directs himself as a man whose ordinary life is the disguise.',
  },
  {
    archive: 'scarlet-street-1945.-hd',
    file: 'Scarlet Street 1945.HD.mp4',
    sizeBytes: 2095322885,
    title: 'Scarlet Street',
    year: 1945,
    runtimeMin: 102,
    genres: ['Crime', 'Drama'],
    director: 'Fritz Lang',
    cast: ['Edward G. Robinson', 'Joan Bennett', 'Dan Duryea'],
    country: 'USA',
    overview: 'A meek cashier and amateur painter is drawn into a cruel game by a woman and her boyfriend. Fritz Lang\'s noir is a quiet study of humiliation that turns very dark.',
  },
  {
    archive: 'meet-john-doe-1941_202309',
    file: 'Meet John Doe (1941).mp4',
    sizeBytes: 339730910,
    title: 'Meet John Doe',
    year: 1941,
    runtimeMin: 123,
    genres: ['Drama', 'Comedy'],
    director: 'Frank Capra',
    cast: ['Gary Cooper', 'Barbara Stanwyck'],
    country: 'USA',
    overview: 'A newspaper invents a everyman who threatens to jump off City Hall on Christmas Eve, then hires an actual drifter to play him. Capra\'s populist fable is funny until it is not.',
  },
  {
    archive: 'ReeferMadness_479',
    file: 'MoviePowderPresentsReeferMadness.mp4',
    sizeBytes: 427129603,
    title: 'Reefer Madness',
    year: 1936,
    runtimeMin: 68,
    genres: ['Drama'],
    director: 'Louis J. Gasnier',
    cast: ['Dorothy Short', 'Kenneth Craig'],
    country: 'USA',
    overview: 'A church-funded scare film follows clean-cut teenagers into madness after one marijuana cigarette. Its wild overstatement made it a midnight-movie comedy decades later.',
  },
  {
    archive: 'CarnivalOfSouls_201508',
    file: 'CarnivalOfSouls.mp4',
    sizeBytes: 857233601,
    title: 'Carnival of Souls',
    year: 1962,
    runtimeMin: 83,
    genres: ['Horror', 'Mystery'],
    director: 'Herk Harvey',
    cast: ['Candace Hilligoss'],
    country: 'USA',
    overview: 'A woman survives a car crash and is drawn to an abandoned carnival pavilion. A dreamlike independent horror film whose cheapness became part of its eerie power.',
  },
  {
    archive: 'the-last-man-on-earth-1964_202312',
    file: 'The Last Man on Earth (1964).mp4',
    sizeBytes: 505893180,
    title: 'The Last Man on Earth',
    year: 1964,
    runtimeMin: 87,
    genres: ['Horror', 'Science Fiction'],
    director: 'Ubaldo Ragona',
    cast: ['Vincent Price'],
    country: 'Italy',
    overview: 'A plague has turned humanity into vampire-like creatures, and one survivor spends his nights barricaded and his days clearing the dead. The first adaptation of Richard Matheson\'s I Am Legend.',
  },
  {
    archive: 'house_on_haunted_hill',
    file: 'house_on_haunted_hill.mp4',
    sizeBytes: 843645455,
    title: 'House on Haunted Hill',
    year: 1959,
    runtimeMin: 75,
    genres: ['Horror', 'Mystery'],
    director: 'William Castle',
    cast: ['Vincent Price', 'Carol Ohmart'],
    country: 'USA',
    overview: 'A millionaire offers five strangers ten thousand dollars each if they survive a night in a haunted house. William Castle\'s gimmick-filled chiller is a great Halloween party.',
  },
  {
    archive: 'SantaClausConquerstheMartians1964',
    file: 'SantaClausConquerstheMartians1964.mp4',
    sizeBytes: 476702930,
    title: 'Santa Claus Conquers the Martians',
    year: 1964,
    runtimeMin: 81,
    genres: ['Comedy', 'Science Fiction', 'Family'],
    director: 'Nicholas Webster',
    cast: ['John Call', 'Leonard Hicks', 'Pia Zadora'],
    country: 'USA',
    overview: 'Martian children are so unhappy that their leaders kidnap Santa Claus and bring him to Mars. A cheerful, cardboard-and-foil holiday oddity.',
  },
  {
    archive: 'MyManGodfrey1936',
    file: 'MyManGodfrey1936.mp4',
    sizeBytes: 590603785,
    title: 'My Man Godfrey',
    year: 1936,
    runtimeMin: 95,
    genres: ['Comedy', 'Romance'],
    director: 'Gregory La Cava',
    cast: ['William Powell', 'Carole Lombard'],
    country: 'USA',
    overview: 'A spoiled heiress finds a homeless man during a scavenger hunt and hires him as the family butler. A screwball comedy with a surprisingly sharp eye on money and class.',
  },
  {
    archive: 'the-awful-truth',
    file: 'The Awful Truth.mp4',
    sizeBytes: 169106272,
    title: 'The Awful Truth',
    year: 1937,
    runtimeMin: 91,
    genres: ['Comedy', 'Romance'],
    director: 'Leo McCarey',
    cast: ['Cary Grant', 'Irene Dunne'],
    country: 'USA',
    overview: 'A divorcing couple keeps sabotaging each other\'s new relationships. Cary Grant and Irene Dunne made bickering look like the best possible form of flirtation.',
  },
  {
    archive: 'NothingSacred',
    file: 'NothingSacred.mp4',
    sizeBytes: 442440689,
    title: 'Nothing Sacred',
    year: 1937,
    runtimeMin: 74,
    genres: ['Comedy', 'Romance'],
    director: 'William A. Wellman',
    cast: ['Carole Lombard', 'Fredric March'],
    country: 'USA',
    overview: 'A reporter turns a small-town woman\'s imaginary illness into a national media circus. A fast, cynical screwball comedy about the business of feeling sorry for someone.',
  },
  {
    archive: 'AfricaScreams',
    file: 'Africa_Screams.mp4',
    sizeBytes: 492202154,
    title: 'Africa Screams',
    year: 1949,
    runtimeMin: 79,
    genres: ['Comedy', 'Adventure'],
    director: 'Charles Barton',
    cast: ['Bud Abbott', 'Lou Costello'],
    country: 'USA',
    overview: 'Abbott and Costello are mistaken for big-game hunters and sent to Africa after a hidden diamond mine. A loose, silly vehicle for the duo\'s best double act.',
  },
  {
    archive: 'penny_serenade',
    file: 'penny_serenade.mp4',
    sizeBytes: 744862582,
    title: 'Penny Serenade',
    year: 1941,
    runtimeMin: 119,
    genres: ['Drama', 'Romance'],
    director: 'George Stevens',
    cast: ['Cary Grant', 'Irene Dunne'],
    country: 'USA',
    overview: 'A couple looks back on their marriage and the child they tried to adopt. A tender, bittersweet melodrama that gives Grant one of his most vulnerable roles.',
  },
  {
    archive: 'the-bat-1959',
    file: 'The Bat (1959).mp4',
    sizeBytes: 468701994,
    title: 'The Bat',
    year: 1959,
    runtimeMin: 80,
    genres: ['Horror', 'Mystery', 'Thriller'],
    director: 'Crane Wilbur',
    cast: ['Vincent Price', 'Agnes Moorehead'],
    country: 'USA',
    overview: 'A mystery writer rents a house where a killer called the Bat is hiding a fortune. A stage-bound old-dark-house mystery with two great scene-chewers.',
  },
];

const BY_ARCHIVE = new Map(CATALOG.map((e) => [e.archive, e]));

function identityOf(entry) {
  return ID_PREFIX + entry.archive;
}

function entryOf(identity) {
  const id = String(identity || '');
  if (!id.startsWith(ID_PREFIX)) return null;
  return BY_ARCHIVE.get(id.slice(ID_PREFIX.length)) || null;
}

function fileUrl(entry) {
  const file = String(entry.file || '').split('/').map(encodeURIComponent).join('/');
  return 'https://archive.org/download/' + encodeURIComponent(entry.archive) + '/' + file;
}

function posterOf(entry) {
  return 'https://archive.org/services/img/' + encodeURIComponent(entry.archive);
}

function humanSize(bytes) {
  if (!Number.isFinite(bytes)) return '';
  if (bytes >= 1e9) return (bytes / 1e9).toFixed(2).replace(/\.?0+$/, '') + ' GB';
  if (bytes >= 1e6) return Math.round(bytes / 1e6) + ' MB';
  return Math.round(bytes / 1e3) + ' KB';
}

function sourceFor(entry) {
  return {
    // A transport word the client already understands: an ordinary HTTP file, not a torrent
    // or a service landing page. `_addon` routes play() back to this provider.
    provider: 'adaptive',
    _addon: PROVIDER_ID,
    label: ['Archive.org', humanSize(entry.sizeBytes)].filter(Boolean).join(' · '),
    kind: 'http',
    quality: null,
    sizeBytes: Number.isFinite(entry.sizeBytes) ? entry.sizeBytes : null,
    risk: null,
    // The ranked rows deliberately do not carry the URL; the addon's own play() does.
    _url: fileUrl(entry),
    _headers: {},
    _langs: ['eng'],
    audioLangs: ['eng'],
    audioTracks: [],
  };
}

function itemFor(entry) {
  return {
    identity: identityOf(entry),
    type: 'movie',
    title: entry.title,
    year: entry.year,
    poster: posterOf(entry),
    backdrop: posterOf(entry),
    logo: null,
    // No invented IMDb score: the catalogue is curated, not rated.
    rating: null,
    match: null,
    maturity: null,
    durationMs: Number.isFinite(entry.runtimeMin) ? entry.runtimeMin * 60000 : null,
    languages: ['eng'],
    genres: entry.genres.slice(),
    addedAt: null,
    releasedAt: Date.UTC(entry.year, 0, 1),
    inCinemas: false,
    live: null,
    progress: null,
    sources: [sourceFor(entry)],
    overview: entry.overview,
    tagline: null,
    cast: entry.cast.slice(),
    crew: entry.director ? [entry.director] : [],
    country: entry.country || null,
    _addons: [PROVIDER_ID],
  };
}

function searchText(entry) {
  return [
    entry.title,
    entry.year,
    entry.genres.join(' '),
    entry.director,
    entry.cast.join(' '),
    entry.overview,
  ].join(' ').toLowerCase();
}

function playFor(identity) {
  const entry = entryOf(identity);
  if (!entry) return null;
  return {
    url: fileUrl(entry),
    headers: {},
    kind: 'http',
    expiresAt: null,
  };
}

const GENRES = [...new Set(CATALOG.flatMap((e) => e.genres))]
  .sort((a, b) => a.localeCompare(b));

module.exports = {
  id: PROVIDER_ID,
  name: 'Public Domain Movies',
  native: true,
  // A shop-front default, not a migration: core/addons.js must not append this provider to an
  // existing data/addons.json. A fresh install includes it; an existing owner opts in by adding
  // the native entry from addons.example.json.
  freshInstallOnly: true,
  kind: 'title',
  // Below the owner's own local files and live sources, above remote aggregators: nothing here
  // collides with another addon's identities, but a local copy should still win a title.
  priority: 35,

  async catalogs() {
    return [{
      id: CATALOG_ID,
      name: 'Public Domain Movies',
      type: 'movie',
      extra: ['search', 'genre'],
      // The Categories screen offers exactly these words; the provider answers them itself.
      genreOptions: GENRES.slice(),
    }];
  },

  /** Cache-only by construction: the catalogue is a constant in this file. */
  async catalog(catalogId, extra = {}) {
    if (catalogId !== CATALOG_ID) return [];
    let list = CATALOG.slice();
    const genre = String(extra.genre || '').trim().toLowerCase();
    if (genre) list = list.filter((e) => e.genres.some((g) => g.toLowerCase() === genre));
    const query = String(extra.search || '').trim().toLowerCase();
    if (query) list = list.filter((e) => searchText(e).includes(query));
    const items = list.map(itemFor);
    const limit = Number(extra.limit);
    return Number.isFinite(limit) && limit > 0 ? items.slice(0, limit) : items;
  },

  async meta(identity) {
    const entry = entryOf(identity);
    return entry ? itemFor(entry) : null;
  },

  /** Optional source contribution, so a meta-only path still gets the playable row. */
  async sources(item) {
    const entry = entryOf(item && item.identity);
    return entry ? [sourceFor(entry)] : [];
  },

  /** The native provider's playable answer: one direct HTTP MP4. */
  async play(identity) {
    return playFor(identity);
  },

  /** Alias for the community-protocol word "stream"; same answer as play(). */
  async stream(identity) {
    return playFor(identity);
  },

  /** What this source would hand the player, without a round trip, for the hub's probe. */
  async sourceTarget(identity) {
    const entry = entryOf(identity);
    return entry ? { url: fileUrl(entry), headers: {} } : null;
  },

  stats() {
    return {
      titles: CATALOG.length,
      source: 'archive.org',
      license: 'public domain (US)',
      error: null,
    };
  },
};
