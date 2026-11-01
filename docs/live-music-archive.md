# Live Music Archive integration

## Source baseline

Repository: `sean-v8/DSub2000`, branch `edge`.
GitHub commit: `bb656ad0fc2de8fd3ca51b3743f8aa41b04284bf`.
Canonical baseline: the clean fork commit above. The supplied
`DSub2000-edge-source_bb656ad0.zip` is retained as a separately identified input
snapshot, not assumed byte-identical to that commit.

The supplied ZIP differs from Git in `SettingsFragment.java`,
`DownloadService.java`, and `settings_sync.xml`. Fresh clean-checkout updates
preserve Git's existing settings and add only Archive-specific changes to
DownloadService. For ZIP-based installations, a hash-checked variant retains
its existing queue-sorting and notification changes instead of deleting them.
The updater never writes the unrelated settings files.

No remote push, release, or version change is included.

## Using the browser

Open **Live Music Archive** in the navigation drawer. No Archive.org server,
account, password, or API key is required. Existing Subsonic servers and their
navigation remain available.

The flow is **collection → year → date → recordings → tracks**. Multiple tapes
of the same date remain separate recordings.

- Collections under `etree` are paged alphabetically, 100 results per page.
- **Tapers Section** is also available directly. This is the Archive.org
  `taperssection` collection, unrelated to the Android app of a similar name.
- **Find collection** searches collection titles/identifiers. Clear the search
  to return to the full list.
- **Open collection by identifier** accepts another Archive collection, such as
  `ClubdElf` or `taperssection`.
- Open a collection and choose **Favorite collection**. **Favorite collections**
  returns to those collections quickly and works without a catalogue request.
- Years show recording counts, newest year first. Dates are chronological.
  Undated items have a separate group.
- **Details** includes available creator, venue, coverage, taper, source,
  lineage, description, and item identifier. It also links to Archive.org.
- Tap a track to replace the queue with that recording and start at that track.
  **Play all** starts at the beginning. **Enqueue** appends without clearing the
  current queue. Long-press a track for its individual actions.
- **Audio format** chooses MP3 or FLAC when available. A missing preferred
  format falls back per track; the screen shows the formats actually selected.
  MP3 is the initial default. This preference is independent of server bitrate
  settings and takes effect when loading a recording; existing queue entries
  retain their chosen file.
- **Download recording**, or a track's download action, uses DSub2000's existing
  permanent download mechanism. Playback uses the normal finite-track player,
  including its queue, seeking, and cache.

Original/derivative relationships collapse alternative formats to one track.
MP3 prefers the VBR derivative when alternatives exist. Missing derivative tags
are inherited from the original file. Complete disc/track tags determine order;
otherwise natural filename ordering is used. MP3 and FLAC are the supported
formats in this implementation; it does not attempt to decode SHN originals.

## Download layout and durable offline browsing

New recordings use this layout below the configured music directory:

`Live Music Archive/Artist/Date City - Venue/identifier/`

For example:

`Live Music Archive/Band/2026-10-31 Cambridge, MA - The Sinclair/show.identifier/`

Source/lineage tags are kept in Details, not appended to the folder name. The
identifier has its own directory level, distinguishing multiple tapes of the
same show. Artist and event components are sanitized and limited to 80 and 112
UTF-8 bytes respectively; identifiers remain intact. Missing metadata falls
back to the available date/city/venue or Unknown artist / Undated.

Album labels use the date/city/venue format. Audio filenames retain their stable
hash identities to avoid collisions and preserve queue compatibility. The
persistent catalogue supplies readable track/artist/album metadata even when
embedded audio tags are missing.

Existing downloads from the first Archive build stay in their existing
`Live Music Archive/identifier/` folders. No automatic move takes place while a
track might be playing; opening the recording registers available older files.

Choose **Downloaded recordings** from the Archive menu. Its artist → year →
recording browser uses an indexed SQLite catalogue, with 100 rows per page. It
shows completed permanent downloads, not pending transfers or disposable
playback buffers. Opening a downloaded recording selects only audio actually
present, with per-track format fallback. Unpin/delete operations update that
catalogue; externally removed files are reconciled when their recording opens.

Download metadata lives in the app's database directory, not Android's cache.
Browsing responses and date indexes live in the app's files directory, with a
24-hour refresh age, a 64 MiB / 1,024-file storage bound and oldest-first pruning.
That pruning never deletes the download database or downloaded audio. The
Downloaded recordings browser remains available when browse-history JSON is
absent, expired, or removed. Clearing Android's disposable app cache does not
remove the new catalogue. Clearing all app data/uninstalling is a different
operation and removes app-owned databases.

Existing browse JSON is copied out of the old disposable cache lazily when
accessed. Missing old metadata is not guessed from identifiers.

Items marked `stream_only` or `no-download` use MP3 and do not expose permanent
download actions. That restriction is retained in track identities through
queue serialization. Normal playback can still use temporary player buffering.
Private files and non-audio sidecars are excluded.

## Large-collection policy

Archive browsing does not use DSub's recursive loader, whose MAX_SONGS constant
is 500. That existing server-library behavior is unchanged. The Archive browser
has separate limits:

- Every displayed collection/date/recording/track page contains at most 100 rows.
- Collection and recording listings fetch only lightweight search fields. No
  listing requests recording file metadata or artwork, regardless of size.
- Up to 500 recordings: a date-only index is permitted, in batches of at most
  500 identifiers/dates. The entire index must complete before it is saved.
- Larger collections: request a count and earliest/latest dated bounds. Display
  a bounded year range, then load dates only for the selected year. Some years
  in that range may have no recordings; the UI says so explicitly.
- A selected year above 500 recordings uses a bounded calendar (365/366 days),
  with recording results requested only when a date opens. Empty days are
  possible and explicitly described by the UI. No full-year census is fetched.
- Only an opened recording loads its file metadata; a metadata response is
  limited to 8 MiB, with at most 5,000 file records and 500 playable tracks.
  Oversized items fail visibly with an Archive.org alternative rather than
  silently truncating the recording or recursively loading a collection.

Artwork remains available for an explicitly played/downloaded recording through
DSub's existing artwork mechanism; it is never prefetched for collection lists.

## Player and Subsonic boundaries

Archive tracks use stable IDs starting with `archive:` or `archive-stream:`.
No new fields or subclasses were added to `MusicDirectory.Entry`, preserving
its existing serialized form. Tracks retain recording, filename and format
identity across queue restoration.

Audio uses Archive.org HTTPS URLs, without Subsonic authentication or URL
rewriting. Storage-node redirects are checked, resumes validate Content-Range,
and HTML/error responses are rejected. Known file sizes prevent truncated
Archive downloads from being marked complete.

Cached audio is isolated under its readable event and identifier folders, with a
stable filename hash, so different tapes and formats do not collide. Artwork
uses Archive's image endpoint and the existing local cover-art cache.

Server-only rating, star, playlist and bookmark operations are not implemented
for Archive items. Archive tracks do not scrobble to the configured Subsonic
server. Mixed-source queues are retained locally; they are not sent as partial
queues to a server that cannot represent Archive tracks. A defensive request
check also prevents namespaced Archive IDs reaching Subsonic API parameters.

## Android Auto and voice

The shared Media3 library includes **Live Music Archive** and the persistent
Downloaded recordings browser, with favorite
collections, the `taperssection` collection, and paged collection discovery.
It follows the same year/date/recording hierarchy and provides recording play,
shuffle and append actions. Track selection resolves the recording through the
Archive client, including bare media IDs after browser cache eviction.

This is implementation in the shared browser/session layer. The actual Android
Auto host, a head unit and voice assistants require separate end-to-end device
validation. Existing voice search continues to search the Subsonic library;
Archive-specific natural-language voice search is not included.

## Implementation map

- `archive/ArchiveSource.java`: stable IDs, validation and URL construction.
- `archive/ArchiveCatalog.java`: metadata, format selection and track ordering.
- `archive/ArchiveHttp.java`: independent Archive HTTP transport.
- `archive/ArchiveClient.java`: paging, cursor date indexes, metadata cache and
  conversion to existing song entries.
- `archive/ArchiveMedia.java`: artwork retrieval/cache.
- `archive/ArchivePaths.java`: short, sanitized event/artist folder components.
- `archive/ArchiveLibrary.java`: durable indexed download catalogue.
- `fragments/ArchiveFragment.java`: native browser and recording actions.
- Existing playback, file-cache and Media3 classes contain focused source
  routing and server-boundary changes.

The initial Archive interface uses English fallback strings. Existing language
resources are unchanged.

## Local verification commands

Use JDK 17 and an Android SDK with platform 36; Gradle/AGP may install its
required Build Tools. From the checkout:

```sh
./gradlew :app:assembleFlossDebug :app:assembleGoogleDebug \
  :app:testFlossDebugUnitTest :app:assembleFlossDebugAndroidTest
./gradlew :app:assembleFlossRelease
./gradlew :app:lintFlossDebug
```

With a connected test device/emulator:

```sh
./gradlew :app:connectedFlossDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=github.paroj.dsub2000.service.ArchiveIntegrationTest
```

The unit tests use synthetic metadata and fake HTTP connections; they make no
network requests. Android integration tests seed offline metadata and exercise
the real song models, cache paths, recording screen, shared Media3 catalogue,
queue resolution and local MP3 playback/seeking. The tiny 440 Hz MP3 asset is a
generated test tone, not a recording from Archive.org.

See `VERIFICATION.md` in the delivery package for the checks actually completed,
including the supplied-baseline lint comparison and remaining device work.
