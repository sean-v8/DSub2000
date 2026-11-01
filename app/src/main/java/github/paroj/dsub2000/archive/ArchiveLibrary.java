package github.paroj.dsub2000.archive;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.service.DownloadFile;
import github.paroj.dsub2000.util.FileUtil;

/** Durable download metadata. Browsing this index never scans a remote collection. */
public final class ArchiveLibrary extends SQLiteOpenHelper {
	private static ArchiveLibrary instance;
	private static final String SAVED = "EXISTS (SELECT 1 FROM tracks t WHERE t.recording=r.id AND t.saved=1)";
	private final Context context;
	private final LruCache<String, String> folders = new LruCache<>(256);

	public static synchronized ArchiveLibrary get(Context context) {
		if(instance == null) instance = new ArchiveLibrary(context.getApplicationContext());
		return instance;
	}

	private ArchiveLibrary(Context context) {
		super(context, "ArchiveDownloads.db", null, 1);
		this.context = context;
	}

	@Override public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, artist TEXT NOT NULL, title TEXT, album TEXT, date TEXT NOT NULL, city TEXT, venue TEXT, details TEXT, folder TEXT NOT NULL)");
		db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, recording TEXT NOT NULL, title TEXT, album TEXT, filename TEXT, diskname TEXT, suffix TEXT, ordinal INTEGER, disc INTEGER, duration INTEGER, bitrate INTEGER, size INTEGER, saved INTEGER NOT NULL DEFAULT 0)");
		db.execSQL("CREATE INDEX tracks_recording ON tracks(recording,saved)");
		db.execSQL("CREATE INDEX tracks_diskname ON tracks(recording,diskname)");
		db.execSQL("CREATE INDEX recordings_artist_date ON recordings(artist,date)");
	}

	@Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		throw new IllegalStateException("Unsupported Archive catalogue migration");
	}

	public synchronized String folder(String id, String proposed) {
		String cached = folders.get(id);
		if(cached != null) return cached;
		try(Cursor row = getReadableDatabase().rawQuery("SELECT folder FROM recordings WHERE id=?", new String[]{id})) {
			if(row.moveToFirst() && ArchivePaths.validFolder(row.getString(0), id)) {
				folders.put(id, row.getString(0)); return row.getString(0);
			}
		}
		// Older downloaded files are left in place, including restored queues.
		String legacy = ArchivePaths.ROOT + "/" + id;
		String chosen = new File(FileUtil.getMusicDirectory(context), legacy).isDirectory() ? legacy : proposed;
		if(!ArchivePaths.validFolder(chosen, id)) chosen = legacy;
		folders.put(id, chosen);
		return chosen;
	}

	public synchronized void remember(ArchiveCatalog.Recording recording) {
		SQLiteDatabase db = getWritableDatabase();
		String folder = folder(recording.id, recording.folder);
		recording.folder = folder;
		db.beginTransaction();
		try {
			ContentValues values = new ContentValues();
			values.put("id", recording.id); values.put("artist", recording.artist);
			values.put("title", recording.title);values.put("album",ArchivePaths.album(recording)); values.put("date", recording.date);
			values.put("city", recording.city); values.put("venue", recording.venue);
			values.put("details", recording.details); values.put("folder", folder);
			db.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
			for(ArchiveCatalog.Track track : recording.tracks) {
				ContentValues t = new ContentValues();
				t.put("id", track.id); t.put("recording", recording.id); t.put("title", track.title);
				t.put("album", track.album); t.put("filename", track.filename);
			t.put("diskname", github.paroj.dsub2000.util.Util.md5Hex(track.id) + "." + track.suffix); t.put("suffix", track.suffix);
				t.put("ordinal", track.track); t.put("disc", track.disc); t.put("duration", track.duration);
				t.put("bitrate", track.bitrate); t.put("size", track.size);
				File saved = new File(new File(FileUtil.getMusicDirectory(context), folder),
						github.paroj.dsub2000.util.Util.md5Hex(track.id) + "." + track.suffix);
				if(!recording.streamOnly && saved.isFile()) t.put("saved", 1);
				// Updating metadata must not reset an existing completed download.
				if(db.update("tracks", t, "id=?", new String[]{track.id}) == 0) db.insertOrThrow("tracks", null, t);
			}
			db.setTransactionSuccessful();
		} finally { db.endTransaction(); }
	}

	public synchronized void completed(DownloadFile file) {
		if(!ArchiveSource.isArchive(file.getSong().getId()) || ArchiveSource.isStreamOnly(file.getSong().getId())) return;
		ContentValues values = new ContentValues(); values.put("saved", file.isSaved() ? 1 : 0);
		getWritableDatabase().update("tracks", values, "id=?", new String[]{file.getSong().getId()});
	}

	public ArchiveClient.Page artists(int page) {
		String sql = "SELECT artist,artist,'',COUNT(*) FROM recordings r WHERE " + SAVED + " GROUP BY artist";
		return page(sql, new String[0], " ORDER BY artist COLLATE NOCASE", page);
	}

	public ArchiveClient.Page years(String artist, int page) {
		String sql = "SELECT CASE WHEN date='' THEN 'unknown' ELSE substr(date,1,4) END AS year, CASE WHEN date='' THEN 'Undated' ELSE substr(date,1,4) END,'',COUNT(*) FROM recordings r WHERE artist=? AND " + SAVED + " GROUP BY year";
		return page(sql, new String[]{artist}, " ORDER BY year DESC", page);
	}

	public ArchiveClient.Page recordings(String artist, String year, int page) {
		String sql = "SELECT id,album,id,0 FROM recordings r WHERE artist=? AND "
				+ (year.equals("unknown") ? "date=''" : "substr(date,1,4)=?") + " AND " + SAVED;
		return page(sql, year.equals("unknown") ? new String[]{artist} : new String[]{artist,year}, " ORDER BY date,id", page);
	}

	private synchronized ArchiveClient.Page page(String sql, String[] args, String order, int page) {
		if(page < 1) throw new IllegalArgumentException("Invalid local page");
		ArchiveClient.Page result = new ArchiveClient.Page(); result.rows = new ArrayList<>();
		try(Cursor count = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM (" + sql + ")", args)) {
			count.moveToFirst(); result.total = count.getInt(0);
		}
		try(Cursor row = getReadableDatabase().rawQuery(sql + order + " LIMIT " + ArchiveClient.PAGE_SIZE
				+ " OFFSET " + ((long) (page - 1) * ArchiveClient.PAGE_SIZE), args)) {
			while(row.moveToNext()) result.rows.add(new ArchiveCatalog.Row(row.getString(0),row.getString(1),row.getString(2),row.getInt(3)));
		}
		return result;
	}

	/** Reconstruct tagged entries even if an Archive file itself lacks audio tags. */
	public synchronized Entry entryForFile(File file) {
		if(!file.isFile() || file.getParentFile()==null) return null;
		String disk=file.getName().replace(".complete.",".");
		try(Cursor row=getReadableDatabase().rawQuery("SELECT t.id,t.title,t.album,r.artist,t.ordinal,t.disc,t.duration,t.bitrate,t.size,t.suffix,r.folder,t.filename FROM tracks t JOIN recordings r ON r.id=t.recording WHERE t.recording=? AND t.diskname=?",new String[]{file.getParentFile().getName(),disk})) {
			if(!row.moveToFirst()) return null;
			File expected=new File(new File(FileUtil.getMusicDirectory(context),row.getString(10)),file.getName());
			if(!expected.getAbsolutePath().equals(file.getAbsolutePath())) return null;
			Entry entry=new Entry(row.getString(0));entry.setTitle(row.getString(1));entry.setAlbum(row.getString(2));entry.setArtist(row.getString(3));
			entry.setTrack(row.getInt(4));entry.setDiscNumber(row.getInt(5));entry.setDuration(row.getInt(6));entry.setBitRate(row.getInt(7));entry.setSize(row.getLong(8));entry.setSuffix(row.getString(9));entry.setPath(row.getString(10)+"/"+row.getString(11));
			entry.setParent(ArchiveSource.itemId(ArchiveSource.identifier(entry.getId())));entry.setAlbumId(entry.getParent());entry.setCoverArt(entry.getParent());
			entry.setContentType("flac".equals(entry.getSuffix()) ? "audio/flac" : "audio/mpeg");return entry;
		}
	}

	public synchronized ArchiveCatalog.Recording savedRecording(String id, ArchiveCatalog.Format format) {
		ArchiveCatalog.Recording recording = new ArchiveCatalog.Recording(); recording.id = id;
		try(Cursor row = getReadableDatabase().rawQuery("SELECT artist,title,date,city,venue,details,folder FROM recordings WHERE id=?", new String[]{id})) {
			if(!row.moveToFirst()) return null;
			recording.artist=row.getString(0); recording.title=row.getString(1); recording.date=row.getString(2);
			recording.city=row.getString(3); recording.venue=row.getString(4); recording.details=row.getString(5); recording.folder=row.getString(6);
		}
		java.util.Map<String, ArchiveCatalog.Track> selected = new java.util.TreeMap<>();
		try(Cursor row = getReadableDatabase().rawQuery("SELECT id,title,album,filename,suffix,ordinal,disc,duration,bitrate,size FROM tracks WHERE recording=? AND saved=1 ORDER BY ordinal,id", new String[]{id})) {
			while(row.moveToNext()) {
				ArchiveCatalog.Track track = new ArchiveCatalog.Track();
				track.id=row.getString(0); track.title=row.getString(1); track.album=row.getString(2);
				track.filename=row.getString(3); track.suffix=row.getString(4); track.track=row.getInt(5);
				track.disc=row.getInt(6); track.duration=row.getInt(7); track.bitrate=row.getInt(8); track.size=row.getLong(9); track.artist=recording.artist;
				File file = new File(new File(FileUtil.getMusicDirectory(context),recording.folder),
						github.paroj.dsub2000.util.Util.md5Hex(track.id) + "." + track.suffix);
				if(!file.isFile()) {
					ContentValues missing=new ContentValues(); missing.put("saved",0);
					getWritableDatabase().update("tracks",missing,"id=?",new String[]{track.id}); continue;
				}
				String key=String.format(java.util.Locale.US,"%06d",track.track);
				ArchiveCatalog.Track prior=selected.get(key);
				String preferred=format==ArchiveCatalog.Format.FLAC ? "flac" : "mp3";
				if(prior==null || track.suffix.equals(preferred)) selected.put(key,track);
			}
		}
		recording.tracks.addAll(selected.values());
		return recording.tracks.isEmpty() ? null : recording;
	}
}
