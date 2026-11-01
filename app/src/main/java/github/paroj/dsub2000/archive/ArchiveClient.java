package github.paroj.dsub2000.archive;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import github.paroj.dsub2000.domain.MusicDirectory;
import github.paroj.dsub2000.util.ProgressListener;
import github.paroj.dsub2000.util.Util;

/** Paged discovery and a small, server-independent metadata cache. */
public final class ArchiveClient {
	public static final int PAGE_SIZE = 100;
	public static final int INDEX_LIMIT = 500;
	private static final int INDEX_BATCH = 500;
	private static final Object CACHE_LOCK = new Object();
	private static final long CACHE_AGE = 24L * 60 * 60 * 1000;
	private final Context context;
	private boolean cached;
	public interface JsonReader { JSONObject read(String url) throws Exception; }
	private final JsonReader network;
	private final boolean virtualNetwork;

	public ArchiveClient(Context context) {
		this(context, url -> ArchiveHttp.json(url, 8 * 1024 * 1024),false);
	}

	public ArchiveClient(Context context, JsonReader network) {
		this(context,network,true);
	}

	private ArchiveClient(Context context,JsonReader network,boolean virtualNetwork) {
		this.virtualNetwork=virtualNetwork;
		this.context = context.getApplicationContext();
		this.network = network;
	}

	public boolean isCached() { return cached; }

	public static final class Page {
		public List<ArchiveCatalog.Row> rows;
		public int total;
	}

	public Page collections(String search, int page, boolean refresh) throws Exception {
		String query = "(collection:etree AND mediatype:collection) OR identifier:taperssection";
		if(!search.trim().isEmpty()) {
			// Quoting prevents user input from escaping the supported roots.
			query = "(" + query + ") AND (title:" + ArchiveSource.quoted(search.trim())
					+ " OR identifier:" + ArchiveSource.quoted(search.trim()) + ")";
		}
		return search(query, "titleSorter asc", page, refresh);
	}

	public Page recordings(String collection, String date, boolean refresh, int page) throws Exception {
		ArchiveSource.validateIdentifier(collection);
		String query = "collection:" + ArchiveSource.quoted(collection) + " AND mediatype:(etree OR audio)";
		if(date.equals("unknown")) {
			query += " AND -date:[* TO *]";
		} else {
			if(!date.equals(ArchiveCatalog.date(date))) throw new IllegalArgumentException("Invalid recording date");
			query += " AND date:[" + date + "T00:00:00Z TO " + date + "T23:59:59Z]";
		}
		return search(query, "identifier asc", page, refresh);
	}

	private Page search(String query, String sort, int page, boolean refresh) throws Exception {
		if(page < 1) throw new IllegalArgumentException("Invalid Archive page");
		String url = "https://archive.org/advancedsearch.php?q=" + ArchiveSource.encode(query)
				+ (sort.equals("identifier asc") ? "&fl[]=identifier&fl[]=title&fl[]=venue&fl[]=coverage" : "&fl[]=identifier&fl[]=title")
				+ "&sort[]=" + ArchiveSource.encode(sort) + "&rows=" + PAGE_SIZE + "&page=" + page + "&output=json";
		JSONObject json = fetch(url, refresh);
		if(json.getJSONObject("response").getJSONArray("docs").length()>PAGE_SIZE) throw new IOException("Archive.org exceeded the requested page size");
		Page result = new Page();
		result.rows = ArchiveCatalog.searchRows(json);
		result.total = json.getJSONObject("response").getInt("numFound");
		return result;
	}

	private String collectionQuery(String collection, String year) {
		ArchiveSource.validateIdentifier(collection);
		String query = "collection:" + ArchiveSource.quoted(collection) + " AND mediatype:(etree OR audio)";
		if(year != null) {
			if(!year.matches("[0-9]{4}")) throw new IllegalArgumentException("Invalid Archive year");
			query += " AND date:[" + year + "-01-01T00:00:00Z TO " + year + "-12-31T23:59:59Z]";
		}
		return query;
	}

	private int count(String query, boolean refresh) throws Exception {
		JSONObject response = fetch("https://archive.org/advancedsearch.php?q=" + ArchiveSource.encode(query)
				+ "&rows=0&output=json", refresh);
		return response.getJSONObject("response").getInt("numFound");
	}

	public List<ArchiveCatalog.Row> years(String collection, boolean refresh, ProgressListener progress) throws Exception {
		String query = collectionQuery(collection, null);
		// A previously complete index can be used offline, without requesting a count.
		File index = cacheFile("timeline-v1:" + collection);
		if(index.isFile() && !refresh && (offline() || fresh(index))) {
			cached = true; return ArchiveCatalog.years(timelineValues(read(index)));
		}
		if(count(query, refresh) <= INDEX_LIMIT) {
			try { return ArchiveCatalog.years(scanDates(collection, null, refresh, progress)); }
			catch(IndexTooLarge changed) { /* Collection grew since its count query. */ }
		}
		index.delete();
		String dated = query + " AND date:[1800-01-01T00:00:00Z TO 2200-12-31T23:59:59Z]";
		String earliest = bound(dated, "asc", refresh), latest = bound(dated, "desc", refresh);
		List<ArchiveCatalog.Row> result = new ArrayList<>();
		if(!earliest.isEmpty() && !latest.isEmpty()) {
			int first=Integer.parseInt(earliest.substring(0,4)), last=Integer.parseInt(latest.substring(0,4));
			for(int year=last;year>=first;year--) result.add(new ArchiveCatalog.Row(Integer.toString(year),Integer.toString(year),context.getString(github.paroj.dsub2000.R.string.archive_lazy_year),0));
		}
		int undated=count(query + " AND -date:[* TO *]", refresh);
		if(undated>0) result.add(new ArchiveCatalog.Row("unknown","Undated","",undated));
		return result;
	}

	private String bound(String query, String direction, boolean refresh) throws Exception {
		JSONObject json=fetch("https://archive.org/advancedsearch.php?q=" + ArchiveSource.encode(query)
				+ "&fl[]=date&rows=1&sort[]=date%20" + direction + "&output=json",refresh);
		JSONArray docs=json.getJSONObject("response").getJSONArray("docs");
		return docs.length()==0 ? "" : ArchiveCatalog.date(ArchiveCatalog.text(docs.getJSONObject(0),"date"));
	}

	public List<ArchiveCatalog.Row> dates(String collection, String year, boolean refresh, ProgressListener progress) throws Exception {
		String query=collectionQuery(collection,year);
		File complete=cacheFile("timeline-v1:"+collection);
		if(complete.isFile() && !refresh && (offline() || fresh(complete))) {
			cached=true;return ArchiveCatalog.dates(timelineValues(read(complete)),year);
		}
		File index=cacheFile("timeline-v2:" + collection + ":" + year);
		if(index.isFile() && !refresh && (offline() || fresh(index))) {
			cached=true; return ArchiveCatalog.dates(timelineValues(read(index)),year);
		}
		if(count(query,refresh)<=INDEX_LIMIT) {
			try { return ArchiveCatalog.dates(scanDates(collection,year,refresh,progress),year); }
			catch(IndexTooLarge changed) { /* Use a bounded calendar instead. */ }
		}
		index.delete();
		// No giant census: at most 366 lightweight days, then paged per-date search.
		java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US);
		java.util.Calendar calendar=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"),java.util.Locale.US);
		format.setTimeZone(calendar.getTimeZone()); calendar.clear();calendar.set(Integer.parseInt(year),0,1);
		List<ArchiveCatalog.Row> days=new ArrayList<>();
		while(calendar.get(java.util.Calendar.YEAR)==Integer.parseInt(year)) {
			String day=format.format(calendar.getTime());
			days.add(new ArchiveCatalog.Row(day,day,context.getString(github.paroj.dsub2000.R.string.archive_lazy_day),0));
			calendar.add(java.util.Calendar.DATE,1);
		}
		return days;
	}

	public Map<String, Integer> timeline(String collection, boolean refresh, ProgressListener progress) throws Exception {
		File file=cacheFile("timeline-v1:" + collection);
		if(file.isFile() && !refresh && (offline() || fresh(file))) { cached=true;return timelineValues(read(file)); }
		if(count(collectionQuery(collection,null),refresh)>INDEX_LIMIT) throw new IndexTooLarge();
		return scanDates(collection,null,refresh,progress);
	}

	private static class IndexTooLarge extends IOException {
		IndexTooLarge() { super("Collection is large; open a year instead of loading its full date index."); }
	}

	private Map<String,Integer> scanDates(String collection,String year,boolean refresh,ProgressListener progress) throws Exception {
		File file=cacheFile(year==null ? "timeline-v1:"+collection : "timeline-v2:"+collection+":"+year);
		if(file.isFile() && !refresh && (offline() || fresh(file))) { cached=true;return timelineValues(read(file)); }
		if(offline()) throw new IOException("This date index has not been saved. Use Downloaded recordings for offline audio.");
		Map<String,Integer> dates=new TreeMap<>();String cursor="";Set<String> seen=new HashSet<>();int loaded=0;
		try {
			do {
				if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
				String url="https://archive.org/services/search/v1/scrape?q=" + ArchiveSource.encode(collectionQuery(collection,year)) + "&fields=identifier,date&count=" + INDEX_BATCH;
				if(!cursor.isEmpty()) url+="&cursor="+ArchiveSource.encode(cursor);
				JSONObject json=network.read(url);JSONArray items=json.getJSONArray("items");
				loaded+=items.length();
				if(loaded>INDEX_LIMIT || items.length()>INDEX_BATCH) throw new IndexTooLarge();
				for(int i=0;i<items.length();i++) {
					String date=ArchiveCatalog.date(ArchiveCatalog.text(items.getJSONObject(i),"date"));
					if(date.isEmpty()) date="unknown";dates.put(date,dates.getOrDefault(date,0)+1);
				}
				if(progress!=null) progress.updateProgress("Loaded " + loaded + " recording dates");
				cursor=json.optString("cursor","");if(cursor.equals("null")) cursor="";
				if(!cursor.isEmpty() && !seen.add(cursor)) throw new IOException("Archive.org repeated a search cursor");
			} while(!cursor.isEmpty());
			write(file,new JSONObject(dates));return dates;
		} catch(Exception e) {
			if(!refresh && file.isFile() && !(e instanceof IndexTooLarge) && !(e instanceof java.io.InterruptedIOException)) { cached=true;return timelineValues(read(file)); }
			throw e;
		}
	}

	private Map<String, Integer> timelineValues(JSONObject json) throws Exception {
		Map<String, Integer> dates = new TreeMap<>();
		java.util.Iterator<String> keys = json.keys();
		while(keys.hasNext()) {
			String key = keys.next();
			if(key.equals("unknown") || !ArchiveCatalog.date(key).isEmpty()) dates.put(key, json.getInt(key));
		}
		return dates;
	}

	public ArchiveCatalog.Recording recording(String identifier, ArchiveCatalog.Format format, boolean refresh) throws Exception {
		ArchiveSource.validateIdentifier(identifier);
		return recording(identifier,format,refresh,false);
	}

	public ArchiveCatalog.Recording recording(String identifier, ArchiveCatalog.Format format, boolean refresh, boolean localOnly) throws Exception {
		ArchiveSource.validateIdentifier(identifier);
		if(localOnly || offline()) {
			ArchiveCatalog.Recording saved=ArchiveLibrary.get(context).savedRecording(identifier,format);
			if(saved!=null) { cached=true;return saved; }
			if(localOnly) throw new IOException("No downloaded tracks remain for this recording.");
		}
		return ArchiveCatalog.recording(identifier, fetch("https://archive.org/metadata/" + identifier, refresh), format);
	}

	public List<MusicDirectory.Entry> entries(ArchiveCatalog.Recording recording) {
		ArchiveLibrary.get(context).remember(recording);
		List<MusicDirectory.Entry> entries = new ArrayList<>();
		for(ArchiveCatalog.Track track : recording.tracks) {
			MusicDirectory.Entry entry = new MusicDirectory.Entry(recording.streamOnly ? track.id.replace(ArchiveSource.PREFIX, ArchiveSource.STREAM_PREFIX) : track.id);
			entry.setTitle(track.title);
			entry.setAlbum(track.album);
			entry.setArtist(track.artist);
			entry.setParent(ArchiveSource.itemId(recording.id));
			entry.setAlbumId(entry.getParent());
			entry.setCoverArt(entry.getParent());
			entry.setTrack(track.track);
			entry.setDiscNumber(track.disc);
			entry.setDuration(track.duration);
			entry.setSize(track.size);
			int bitrate = track.bitrate;
			if(bitrate <= 0 && track.duration > 0 && track.size > 0) {
				bitrate = (int) Math.min(Integer.MAX_VALUE, track.size / track.duration * 8 / 1000);
			}
			entry.setBitRate(bitrate > 0 ? bitrate : null);
			entry.setSuffix(track.suffix);
			entry.setContentType(track.suffix.equals("flac") ? "audio/flac" : "audio/mpeg");
			entry.setPath(recording.folder + "/" + track.filename);
			if(!recording.date.isEmpty()) entry.setYear(Integer.parseInt(recording.date.substring(0, 4)));
			entries.add(entry);
		}
		return entries;
	}

	private boolean offline() { return Util.isOffline(context) || (!virtualNetwork && !Util.isNetworkConnected(context)); }
	private boolean fresh(File file) { return System.currentTimeMillis() - file.lastModified() < CACHE_AGE; }

	private JSONObject fetch(String url, boolean refresh) throws Exception {
		File file = cacheFile(url);
		if((!refresh || offline()) && file.isFile() && (offline() || fresh(file))) {
			cached = true;
			return read(file);
		}
		if(offline()) throw new IOException("This page has not been cached. Connect to load it.");
		try {
			JSONObject json = network.read(url);
			write(file, json);
			return json;
		} catch(Exception e) {
			if(!refresh && file.isFile() && !(e instanceof java.io.InterruptedIOException)) {
				cached = true;
				return read(file);
			}
			throw e;
		}
	}

	private File cacheFile(String key) {
		File directory = new File(context.getFilesDir(), "archive-catalog");
		directory.mkdirs();
		File result=new File(directory,Util.md5Hex(key)+".json");
		File legacy=new File(new File(context.getCacheDir(),"archive-catalog"),result.getName());
		if(!result.exists() && legacy.isFile()) {
			synchronized(CACHE_LOCK) {
				try { write(result,read(legacy)); } catch(Exception ignored) { /* Keep the legacy file intact. */ }
			}
		}
		return result;
	}

	private JSONObject read(File file) throws Exception {
		synchronized(CACHE_LOCK) {
			try(java.io.InputStream input = new AtomicFile(file).openRead();
					java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
				byte[] bytes = new byte[16384];
				int n;
				while((n = input.read(bytes)) != -1) {
					if(output.size()+n>8*1024*1024) throw new IOException("Saved Archive metadata is too large");
					output.write(bytes,0,n);
				}
				return new JSONObject(output.toString("UTF-8"));
			}
		}
	}

	// This bounded browsing store is separate from the never-pruned download DB.
	private void trimBrowseStore(File current) {
		File[] files=current.getParentFile().listFiles((dir,name)->name.endsWith(".json"));
		if(files==null) return;
		long bytes=0;for(File file:files) bytes+=file.length();
		if(bytes<=64L*1024*1024 && files.length<=1024) return;
		java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));
		int count=files.length;
		for(File file:files) {
			if(bytes<=64L*1024*1024 && count<=1024) break;
			if(file.equals(current)) continue;
			long length=file.length();if(file.delete()) { bytes-=length;count--; }
		}
	}

	private void write(File file, JSONObject json) throws IOException {
		synchronized(CACHE_LOCK) {
			AtomicFile atomic = new AtomicFile(file);
			FileOutputStream output = null;
			try {
				output = atomic.startWrite();
				output.write(json.toString().getBytes(StandardCharsets.UTF_8));
				atomic.finishWrite(output);
				trimBrowseStore(file);
			} catch(IOException e) {
				if(output != null) atomic.failWrite(output);
				throw e;
			}
		}
	}
}
