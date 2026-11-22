package github.paroj.dsub2000.service;

import github.paroj.dsub2000.archive.ArchiveCatalog;

import github.paroj.dsub2000.archive.ArchiveClient;
import github.paroj.dsub2000.archive.ArchiveLibrary;

import github.paroj.dsub2000.archive.ArchiveSource;

import android.os.Bundle;
import android.util.Log;
import android.util.LruCache;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

import github.paroj.dsub2000.R;
import github.paroj.dsub2000.domain.Artist;
import github.paroj.dsub2000.domain.Indexes;
import github.paroj.dsub2000.domain.MusicDirectory;
import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.domain.MusicFolder;
import github.paroj.dsub2000.domain.Playlist;
import github.paroj.dsub2000.domain.PodcastChannel;
import github.paroj.dsub2000.domain.PodcastEpisode;
import github.paroj.dsub2000.domain.SearchCritera;
import github.paroj.dsub2000.domain.SearchResult;
import github.paroj.dsub2000.util.Constants;
import github.paroj.dsub2000.util.Util;

/** Shared catalogue for Media3 browsers and the legacy Android Auto component. */
public final class SessionLibrary {
    public static final String ROOT = "root";
    private final DownloadService service;
    private final LruCache<String, MediaItem> items = new LruCache<>(1000);
    private final LruCache<String, List<MediaItem>> searches = new LruCache<>(10);
    private List<?> catalogueContext;
    private final ListeningExecutorService executor =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    public SessionLibrary(DownloadService service) {
        this.service = service;
        remember(root());
    }

    public MediaItem root() {
        return folder(ROOT, "DSub2000", null);
    }

    public synchronized MediaItem getItem(String id) {
        refreshContext();
        return ROOT.equals(id) ? root() : items.get(id);
    }

    public synchronized void release() {
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof java.util.concurrent.Future<?>) {
                ((java.util.concurrent.Future<?>) pending).cancel(false);
            }
        }
        items.evictAll();
        searches.evictAll();
    }

    public ListenableFuture<List<MediaItem>> children(String parentId, int page, int pageSize) {
        return executor.submit(() -> {
            try {
                if (page < 0 || pageSize < 1) {
                    throw new IllegalArgumentException("Invalid library page");
                }
                List<?> context = refreshContext();
                List<MediaItem> children = loadChildren(parentId);
                publish(context, children);
                long start = (long) page * pageSize;
                if (start >= children.size()) {
                    return Collections.emptyList();
                }
                return new ArrayList<>(children.subList((int) start,
                        (int) Math.min(children.size(), start + pageSize)));
            } catch (Exception error) {
                Log.e("SessionLibrary", "Unable to load library children", error);
                throw error;
            }
        });
    }

    public ListenableFuture<List<MediaItem>> search(String query) {
        return search(query, true);
    }

    public ListenableFuture<List<MediaItem>> searchResults(String query) {
        return search(query, false);
    }

    private ListenableFuture<List<MediaItem>> search(String query, boolean refresh) {
        return executor.submit(() -> {
            try {
                List<?> context = refreshContext();
                if (!refresh) {
                    List<MediaItem> cached = searches.get(query);
                    if (cached != null) {
                        return cached;
                    }
                }
                MusicService music = MusicServiceFactory.getMusicService(service);
                SearchResult found = music.search(new SearchCritera(query, 10, 10, 50), service, null);
                List<MediaItem> results = new ArrayList<>();
                if (found.hasArtists()) {
                    for (Artist artist : found.getArtists()) {
                        results.add(folder("art-" + artist.getId(), artist.getName(), null));
                    }
                }
                if (found.hasAlbums()) {
                    for (Entry album : found.getAlbums()) {
                        results.add(folder("alb-" + album.getId(), album.getAlbumDisplay(), album.getArtist()));
                    }
                }
                if (found.hasSongs()) {
                    for (Entry song : found.getSongs()) {
                        results.add(song(song, song.getId(), null, false, false));
                    }
                }
                List<MediaItem> snapshot = Collections.unmodifiableList(results);
                synchronized (this) {
                    publish(context, snapshot);
                    searches.put(query, snapshot);
                }
                return snapshot;
            } catch (Exception error) {
                Log.e("SessionLibrary", "Unable to search library", error);
                throw error;
            }
        });
    }

    private void remember(MediaItem item) {
        items.put(item.mediaId, item);
    }

    private synchronized List<?> refreshContext() {
        int server = Util.getActiveServer(service);
        android.content.SharedPreferences preferences = Util.getPreferences(service);
        List<?> current = Arrays.asList(server,
                preferences.getString(Constants.PREFERENCES_KEY_SERVER_URL + server, null),
                preferences.getString(Constants.PREFERENCES_KEY_USERNAME + server, null),
                Util.isOffline(service), Util.isTagBrowsing(service),
                Util.getSelectedMusicFolderId(service), Util.getAlbumListsPerFolder(service));
        if (!current.equals(catalogueContext)) {
            items.evictAll();
            searches.evictAll();
            catalogueContext = current;
        }
        return catalogueContext;
    }

    private synchronized void publish(List<?> context, List<MediaItem> results) {
        if (!context.equals(refreshContext()) || executor.isShutdown()) {
            throw new CancellationException("Library context changed while loading");
        }
        for (MediaItem item : results) {
            remember(item);
        }
    }

    private List<MediaItem> loadChildren(String id) throws Exception {
        List<MediaItem> result = new ArrayList<>();
        if (ROOT.equals(id)) {
            result.add(folder("albumLists", service.getString(R.string.main_albums_title), null));
            result.add(folder("library", service.getString(R.string.button_bar_browse), null));
            result.add(folder("playlists", service.getString(R.string.button_bar_playlists), null));
            result.add(folder("ia-root", service.getString(R.string.archive_title), null));
            if (Util.getPreferences(service).getBoolean(Constants.PREFERENCES_KEY_PODCASTS_ENABLED, true)) {
                result.add(folder("podcasts", service.getString(R.string.button_bar_podcasts), null));
            }
            if (Util.getPreferences(service).getBoolean(Constants.PREFERENCES_KEY_BOOKMARKS_ENABLED, true)) {
                result.add(folder("bookmarks", service.getString(R.string.button_bar_bookmarks), null));
            }
            return result;
        }
        if (id.startsWith("ia-")) return archiveChildren(id);
        if ("albumLists".equals(id)) {
            int[] categories = {R.string.main_albums_newest, R.string.main_albums_random,
                    R.string.main_albums_highest, R.string.main_albums_starred,
                    R.string.main_albums_recent, R.string.main_albums_frequent};
            for (int category : categories) {
                if (category != R.string.main_albums_highest || !Util.isTagBrowsing(service)) {
                    result.add(folder("ty-" + category, service.getString(category), null));
                }
            }
            return result;
        }
        if (id.startsWith("md-") || id.startsWith("pl-")) {
            playOptions(result, id.substring(3), id.startsWith("pl-")
                    ? Constants.INTENT_EXTRA_NAME_PLAYLIST_ID : Constants.INTENT_EXTRA_NAME_ID);
            return result;
        }
        MusicService music = MusicServiceFactory.getMusicService(service);
        if (id.startsWith("ty-")) {
            String type;
            switch (Integer.parseInt(id.substring(3))) {
                case R.string.main_albums_newest: type = "newest"; break;
                case R.string.main_albums_random: type = "random"; break;
                case R.string.main_albums_highest: type = "highest"; break;
                case R.string.main_albums_starred: type = "starred"; break;
                case R.string.main_albums_recent: type = "recent"; break;
                case R.string.main_albums_frequent: type = "frequent"; break;
                default: throw new IllegalArgumentException("Unknown album list");
            }
            for (Entry album : music.getAlbumList(type, 20, 0, true, service, null).getChildren(true, false)) {
                result.add(folder("md-" + album.getId(), album.getAlbumDisplay(), album.getArtist()));
            }
        } else if ("library".equals(id) && !Util.isOffline(service) && !Util.isTagBrowsing(service)) {
            for (MusicFolder folder : music.getMusicFolders(false, service, null)) {
                result.add(folder("mf-" + folder.getId(), folder.getName(), null));
            }
        } else if ("library".equals(id) || id.startsWith("mf-")) {
            boolean artists = "library".equals(id);
            Indexes indexes = music.getIndexes(artists ? null : id.substring(3), false, service, null);
            for (Artist artist : indexes.getArtists()) {
                result.add(folder((artists && !Util.isOffline(service) ? "art-" : "mdc-")
                        + artist.getId(), artist.getName(), null));
            }
            if (!artists) {
                for (Entry entry : indexes.getEntries()) {
                    result.add(song(entry, entry.getId(), null, false, false));
                }
            }
        } else if (id.startsWith("mdc-") || id.startsWith("art-") || id.startsWith("alb-")) {
            String parent = id.substring(4);
            MusicDirectory directory;
            boolean artist = id.startsWith("art-");
            boolean album = id.startsWith("alb-");
            if (artist) {
                directory = music.getArtist(parent, "", false, service, null);
            } else if (album) {
                directory = music.getAlbum(parent, "", false, service, null);
            } else {
                directory = music.getMusicDirectory(parent, "", false, service, null);
            }
            if (!artist) {
                playOptions(result, parent, Constants.INTENT_EXTRA_NAME_ID);
            }
            for (Entry entry : directory.getChildren()) {
                if (entry.isDirectory()) {
                    result.add(folder((artist ? "alb-" : "mdc-") + entry.getId(), entry.getTitle(), null));
                } else {
                    result.add(song(entry, entry.getId(), null, false, album || artist));
                }
            }
        } else if ("playlists".equals(id)) {
            for (Playlist playlist : music.getPlaylists(false, service, null)) {
                result.add(folder("pl-" + playlist.getId(), playlist.getName(), null));
            }
        } else if ("podcasts".equals(id)) {
            for (PodcastChannel channel : music.getPodcastChannels(false, service, null)) {
                result.add(folder("po-" + channel.getId(), channel.getName(), null));
            }
        } else if (id.startsWith("po-")) {
            for (Entry entry : music.getPodcastEpisodes(false, id.substring(3), service, null).getSongs()) {
                String subtitle = entry instanceof PodcastEpisode
                        ? Util.formatDate(service, ((PodcastEpisode) entry).getDate(), false) : null;
                result.add(song(entry, "po-" + entry.getId(), subtitle, true, true));
            }
        } else if ("bookmarks".equals(id)) {
            for (Entry entry : music.getBookmarks(false, service, null).getSongs()) {
                String subtitle = entry.getBookmark() == null ? null
                        : Util.formatDuration(entry.getBookmark().getPosition() / 1000);
                result.add(song(entry, entry.getId(), subtitle, false, true));
            }
        } else {
            throw new IllegalArgumentException("Unknown library parent: " + id);
        }
        return result;
    }

    private List<MediaItem> archiveChildren(String id) throws Exception {
        List<MediaItem> result = new ArrayList<>();
        ArchiveClient archive = new ArchiveClient(service);
        if (id.equals("ia-root")) {
            result.add(folder("ia-saved-artists/1",service.getString(R.string.archive_downloaded),null));
            result.add(folder("ia-groups", service.getString(R.string.archive_all_collections), null));
            result.add(folder("ia-years-taperssection", "Tapers Section", null));
            android.content.SharedPreferences prefs = service.getSharedPreferences("archive", android.content.Context.MODE_PRIVATE);
            List<String> favorites = new ArrayList<>(prefs.getStringSet("collections", Collections.emptySet()));
            Collections.sort(favorites);
            for (String collection : favorites) {
                ArchiveSource.validateIdentifier(collection);
                if (!collection.equals("taperssection")) result.add(folder("ia-years-" + collection,
                        prefs.getString("title:" + collection, collection), null));
            }
        } else if (id.equals("ia-groups")) {
            for(char c='A'; c<='Z'; c++) result.add(folder("ia-group/"+c+"/1",Character.toString(c),null));
            result.add(folder("ia-group/%23/1",service.getString(R.string.archive_other_group),null));
        } else if (id.startsWith("ia-group/")) {
            String[] parts=id.split("/",-1);
            if(parts.length!=3) throw new IllegalArgumentException("Invalid Archive group");
            String group=android.net.Uri.decode(parts[1]);
            int page=Integer.parseInt(parts[2]);
            ArchiveClient.Page found=archive.collections("",group,page,false);
            for(ArchiveCatalog.Row row:found.rows) result.add(folder("ia-years-"+row.id,row.title,null));
            if((long)page*ArchiveClient.PAGE_SIZE<found.total) result.add(folder("ia-group/"+parts[1]+"/"+(page+1),service.getString(R.string.archive_next),null));
        } else if (id.startsWith("ia-collections-")) {
            int page = Integer.parseInt(id.substring("ia-collections-".length()));
            if (page < 1) throw new IllegalArgumentException("Invalid Archive page");
            ArchiveClient.Page found = archive.collections("", page, false);
            for (ArchiveCatalog.Row row : found.rows) result.add(folder("ia-years-" + row.id, row.title, null));
            if ((long) page * ArchiveClient.PAGE_SIZE < found.total) result.add(folder("ia-collections-" + (page + 1),
                    service.getString(R.string.archive_next), null));
        } else if (id.startsWith("ia-years-")) {
            String collection = id.substring("ia-years-".length());
            for (ArchiveCatalog.Row row : archive.years(collection, false, null)) {
                result.add(folder(row.id.equals("unknown") ? "ia-recordings-" + collection + "/unknown/1"
                        : "ia-dates-" + collection + "/" + row.id, row.title,
                        row.count>0 ? service.getResources().getQuantityString(R.plurals.archive_recordings,row.count,row.count) : row.detail));
            }
        } else if (id.startsWith("ia-dates-")) {
            String[] parts = id.substring("ia-dates-".length()).split("/", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{4}")) throw new IllegalArgumentException("Invalid Archive year");
            for (ArchiveCatalog.Row row : archive.dates(parts[0], parts[1], false, null)) {
                result.add(folder("ia-recordings-" + parts[0] + "/" + row.id + "/1", row.title,
                        row.count>0 ? service.getResources().getQuantityString(R.plurals.archive_recordings,row.count,row.count) : row.detail));
            }
        } else if (id.startsWith("ia-recordings-")) {
            String[] parts = id.substring("ia-recordings-".length()).split("/", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Invalid Archive date");
            int page = Integer.parseInt(parts[2]);
            if (page < 1) throw new IllegalArgumentException("Invalid Archive page");
            ArchiveClient.Page found = archive.recordings(parts[0], parts[1], false, page);
            for (ArchiveCatalog.Row row : found.rows) result.add(folder("ia-tracks-" + row.id, row.title, row.detail));
            if ((long) page * ArchiveClient.PAGE_SIZE < found.total) result.add(folder("ia-recordings-"
                    + parts[0] + "/" + parts[1] + "/" + (page + 1), service.getString(R.string.archive_next), null));
        } else if (id.startsWith("ia-saved-")) {
            String[] parts=id.split("/",-1);String kind=parts[0];
            ArchiveLibrary library=ArchiveLibrary.get(service);ArchiveClient.Page found;
            if(kind.equals("ia-saved-artists") && parts.length==2) found=library.artists(Integer.parseInt(parts[1]));
            else if(kind.equals("ia-saved-years") && parts.length==3) found=library.years(android.net.Uri.decode(parts[1]),Integer.parseInt(parts[2]));
            else if(kind.equals("ia-saved-shows") && parts.length==4) found=library.recordings(android.net.Uri.decode(parts[1]),parts[2],Integer.parseInt(parts[3]));
            else throw new IllegalArgumentException("Invalid downloaded Archive folder");
            for(ArchiveCatalog.Row row:found.rows) {
                String target=kind.equals("ia-saved-artists") ? "ia-saved-years/"+ArchiveSource.encode(row.id)+"/1"
                        : kind.equals("ia-saved-years") ? "ia-saved-shows/"+parts[1]+"/"+row.id+"/1" : "ia-local-tracks-"+row.id;
                result.add(folder(target,row.title,row.detail));
            }
            int current=Integer.parseInt(parts[parts.length-1]);
            if((long)current*ArchiveClient.PAGE_SIZE<found.total) result.add(folder(id.substring(0,id.lastIndexOf('/')+1)+(current+1),service.getString(R.string.archive_next),null));
        } else if (id.startsWith("ia-tracks-") || id.startsWith("ia-local-tracks-")) {
            boolean localOnly=id.startsWith("ia-local-tracks-");
            String identifier = id.substring(localOnly ? "ia-local-tracks-".length() : "ia-tracks-".length());
            boolean flac = service.getSharedPreferences("archive", android.content.Context.MODE_PRIVATE).getBoolean("flac", false);
            ArchiveCatalog.Recording recording = archive.recording(identifier,
                    flac ? ArchiveCatalog.Format.FLAC : ArchiveCatalog.Format.MP3, false, localOnly);
            List<Entry> entries = archive.entries(recording);
            if (!entries.isEmpty()) playOptions(result, localOnly ? "archive-local:"+identifier : ArchiveSource.itemId(identifier), Constants.INTENT_EXTRA_NAME_ID);
            for (Entry entry : entries) {
                MediaItem item=song(entry,entry.getId(),entry.getAlbum(),false,false);
                if(localOnly) {
                    Bundle extras=new Bundle(item.mediaMetadata.extras);extras.putBoolean("archiveLocalOnly",true);
                    item=item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build();
                }
                result.add(item);
            }
        } else {
            throw new IllegalArgumentException("Unknown Archive folder");
        }
        return result;
    }

    private static MediaItem folder(String id, CharSequence title, CharSequence subtitle) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false).build()).build();
    }

    private MediaItem song(Entry entry, String id, String subtitle, boolean podcast, boolean resume) throws Exception {
        Entry copy = Entry.fromByteArray(entry.toByteArray());
        if (!resume) {
            copy.setBookmark(null);
        }
        Bundle extras = new Bundle();
        extras.putByteArray(Constants.INTENT_EXTRA_ENTRY_BYTES, copy.toByteArray());
        extras.putString(podcast ? Constants.INTENT_EXTRA_NAME_PODCAST_ID
                : Constants.INTENT_EXTRA_NAME_CHILD_ID, entry.getId());
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(entry.getTitle()).setSubtitle(subtitle).setArtist(entry.getArtist())
                .setAlbumTitle(entry.getAlbum()).setIsBrowsable(false).setIsPlayable(true)
                .setExtras(extras).build()).build();
    }

    private void playOptions(List<MediaItem> items, String id, String key) {
        String[] prefixes = {"play-", "shuffle-", "playLast-"};
        int[] titles = {R.string.menu_play, R.string.menu_shuffle, R.string.menu_play_last};
        for (int i = 0; i < prefixes.length; i++) {
            Bundle extras = new Bundle();
            extras.putString(key, id);
            extras.putBoolean(Constants.INTENT_EXTRA_NAME_SHUFFLE, i == 1);
            extras.putBoolean(Constants.INTENT_EXTRA_PLAY_LAST, i == 2);
            items.add(new MediaItem.Builder().setMediaId(prefixes[i] + id)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(service.getString(titles[i]))
                            .setIsBrowsable(false).setIsPlayable(true).setExtras(extras).build()).build());
        }
    }
}
