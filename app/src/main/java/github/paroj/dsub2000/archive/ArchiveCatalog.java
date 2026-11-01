package github.paroj.dsub2000.archive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Parsing is independent of Android so real API fixtures can be tested on a JVM. */
public final class ArchiveCatalog {
	public static final int MAX_FILES = 5000;
	public static final int MAX_TRACKS = 500;
	public enum Format { MP3, FLAC }

	public static final class Row {
		public final String id;
		public final String title;
		public final String detail;
		public final int count;

		public Row(String id, String title, String detail, int count) {
			this.id = id;
			this.title = title;
			this.detail = detail;
			this.count = count;
		}
	}

	public static final class Track {
		public String id;
		public String title;
		public String artist;
		public String album;
		public String filename;
		public String suffix;
		public int track;
		public int disc;
		public int duration;
		public int bitrate;
		public long size;
	}

	public static final class Recording {
		public String id;
		public String title;
		public String artist;
		public String date;
		public String details;
		public String city = "";
		public String venue = "";
		public String folder;
		public boolean streamOnly;
		public List<Track> tracks = new ArrayList<>();
	}

	private ArchiveCatalog() {}

	/** IA fields may be a scalar or an array, even on otherwise similar items. */
	public static String text(JSONObject object, String key) {
		Object value = object.opt(key);
		if(value == null || value == JSONObject.NULL) {
			return "";
		}
		if(value instanceof JSONArray) {
			JSONArray values = (JSONArray) value;
			List<String> strings = new ArrayList<>();
			for(int i = 0; i < values.length(); i++) {
				String s = values.optString(i, "");
				if(!s.isEmpty()) strings.add(s);
			}
			return joinWith("; ", strings);
		}
		return value.toString().trim();
	}

	public static List<Row> searchRows(JSONObject response) throws JSONException {
		JSONArray docs = response.getJSONObject("response").getJSONArray("docs");
		Map<String, Row> rows = new LinkedHashMap<>();
		for(int i = 0; i < docs.length(); i++) {
			JSONObject doc = docs.getJSONObject(i);
			String id = text(doc, "identifier");
			ArchiveSource.validateIdentifier(id);
			String title = clip(text(doc, "title"),512);
			String detail = join(text(doc, "venue"), text(doc, "coverage"),
					id);
			detail=clip(detail,2048);
			if(detail.isEmpty()) detail = id;
			rows.put(id, new Row(id, title.isEmpty() ? id : title, detail, 0));
		}
		return new ArrayList<>(rows.values());
	}

	private static String clip(String value,int limit) { return value.length()<=limit ? value : value.substring(0,limit)+"…"; }

	public static String date(String value) {
		if(value.length() < 10) return "";
		String date = value.substring(0, 10);
		if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return "";
		try {
			java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
			parser.setLenient(false);
			parser.parse(date);
			return date;
		} catch(java.text.ParseException e) {
			return "";
		}
	}

	public static List<Row> years(Map<String, Integer> dates) {
		Map<String, Integer> years = new TreeMap<>(Collections.reverseOrder());
		for(Map.Entry<String, Integer> entry : dates.entrySet()) {
			String year = entry.getKey().equals("unknown") ? "unknown" : entry.getKey().substring(0, 4);
			years.put(year, years.getOrDefault(year, 0) + entry.getValue());
		}
		List<Row> rows = new ArrayList<>();
		for(Map.Entry<String, Integer> entry : years.entrySet()) {
			rows.add(new Row(entry.getKey(), entry.getKey().equals("unknown") ? "Undated" : entry.getKey(), "", entry.getValue()));
		}
		return rows;
	}

	public static List<Row> dates(Map<String, Integer> dates, String year) {
		List<Row> rows = new ArrayList<>();
		for(Map.Entry<String, Integer> entry : new TreeMap<>(dates).entrySet()) {
			if(entry.getKey().startsWith(year)) {
				rows.add(new Row(entry.getKey(), entry.getKey(), "", entry.getValue()));
			}
		}
		return rows;
	}

	public static Recording recording(String identifier, JSONObject json, Format preferred) throws JSONException {
		JSONObject metadata = json.getJSONObject("metadata");
		Recording recording = new Recording();
		recording.id = identifier;
		recording.title = clip(text(metadata, "title"),2048);
		if(recording.title.isEmpty()) recording.title = identifier;
		recording.artist = clip(text(metadata, "creator"),512);
		if(recording.artist.isEmpty()) recording.artist="Unknown artist";
		recording.date = date(text(metadata, "date"));
		recording.city = clip(text(metadata, "coverage"),512);
		if(recording.city.isEmpty()) recording.city = clip(text(metadata, "city"),512);
		recording.venue = clip(text(metadata, "venue"),512);
		recording.folder = ArchivePaths.folder(recording);
		recording.streamOnly = truth(metadata, "stream_only") || truth(metadata, "no-download");
		recording.details = join(recording.artist, recording.date,
				text(metadata, "venue"), text(metadata, "coverage"),
				label("Taper", text(metadata, "taper")),
				label("Source", text(metadata, "source")),
				label("Lineage", text(metadata, "lineage")),
				text(metadata, "description"), identifier);
		recording.details=clip(recording.details,65536);

		JSONArray files = json.getJSONArray("files");
		if(files.length() > MAX_FILES) throw new JSONException("This item has too many files to load safely. Open it on Archive.org.");
		Map<String, JSONObject> byName = new LinkedHashMap<>();
		for(int i = 0; i < files.length(); i++) {
			JSONObject file = files.getJSONObject(i);
			byName.put(text(file, "name"), file);
		}
		Map<String, JSONObject> selected = new LinkedHashMap<>();
		for(JSONObject file : byName.values()) {
			String name = text(file, "name");
			String ext = extension(name);
			if((!ext.equals("mp3") && !ext.equals("flac")) || file.optBoolean("private", false)) continue;
			if(recording.streamOnly && !ext.equals("mp3")) continue;
			try {
				ArchiveSource.trackId(identifier, name);
			} catch(IllegalArgumentException e) {
				continue;
			}
			// Derivative siblings share an original; follow chains without trusting order.
			String key = original(file, byName);
			JSONObject previous = selected.get(key);
			if(previous == null || rank(file, preferred) < rank(previous, preferred)
					|| (rank(file, preferred) == rank(previous, preferred)
					&& name.compareTo(text(previous, "name")) < 0)) {
				selected.put(key, file);
			}
		}
		if(selected.size() > MAX_TRACKS) throw new JSONException("This item exceeds the 500-track safety limit. Open it on Archive.org.");
		List<JSONObject> ordered = new ArrayList<>();
		for(JSONObject file : selected.values()) ordered.add(inheritTags(file, byName));
		// Use tags only if every chosen file has them. Mixing tag and filename
		// comparisons pairwise is not transitive and can crash TimSort.
		boolean completeTracks = true;
		for(JSONObject file : ordered) if(number(text(file, "track")) == 0) completeTracks = false;
		final boolean tagged = completeTracks;
		ordered.sort((a, b) -> {
			int disc = Integer.compare(disc(a), disc(b));
			if(disc != 0) return disc;
			if(tagged) {
				int track = Integer.compare(number(text(a, "track")), number(text(b, "track")));
				if(track != 0) return track;
			}
			return naturalCompare(text(a, "name"), text(b, "name"));
		});
		for(JSONObject file : ordered) {
			Track track = new Track();
			track.filename = text(file, "name");
			track.id = ArchiveSource.trackId(identifier, track.filename);
			track.suffix = extension(track.filename);
			track.title = clip(text(file, "title"),1024);
			if(track.title.isEmpty()) track.title = track.filename;
			track.artist = recording.artist;
			track.album = ArchivePaths.album(recording);
			track.track = recording.tracks.size() + 1;
			track.disc = disc(file);
			track.duration = duration(text(file, "length"));
			try { track.size = Long.parseLong(text(file, "size")); } catch(NumberFormatException ignored) {}
			track.bitrate = number(text(file, "bitrate"));
			recording.tracks.add(track);
		}
		return recording;
	}

	private static boolean truth(JSONObject metadata, String key) {
		String value = text(metadata, key);
		return value.equalsIgnoreCase("true") || value.equals("1");
	}

	private static JSONObject inheritTags(JSONObject file, Map<String, JSONObject> files) throws JSONException {
		JSONObject merged = new JSONObject(file.toString());
		java.util.Set<String> seen = new java.util.HashSet<>();
		JSONObject parent = files.get(text(file, "original"));
		while(parent != null && seen.add(text(parent, "name"))) {
			for(String key : new String[]{"title", "track", "disc", "discnumber", "length"}) {
				if(text(merged, key).isEmpty() && parent.has(key)) merged.put(key, parent.get(key));
			}
			parent = files.get(text(parent, "original"));
		}
		return merged;
	}

	private static int disc(JSONObject file) {
		int disc = number(text(file, "disc"));
		if(disc == 0) disc = number(text(file, "discnumber"));
		if(disc > 0) return disc;
		java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?i)(?:^|[^a-z])d(\\d+)t\\d+").matcher(text(file, "name"));
		return match.find() ? Math.max(1, number(match.group(1))) : 1;
	}

	private static String original(JSONObject file, Map<String, JSONObject> files) {
		String name = text(file, "name");
		java.util.Set<String> seen = new java.util.HashSet<>();
		while(seen.add(name)) {
			JSONObject current = files.get(name);
			if(current == null || text(current, "original").isEmpty()) break;
			name = text(current, "original");
		}
		// No original links: MP3/FLAC with matching stems still represent one track.
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static int rank(JSONObject file, Format preferred) {
		String ext = extension(text(file, "name"));
		if(preferred == Format.FLAC) return ext.equals("flac") ? 0 : 1;
		if(!ext.equals("mp3")) return 10;
		return text(file, "format").equalsIgnoreCase("VBR MP3") ? 0 : 1;
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
	}

	public static int number(String value) {
		try { return Math.max(0, Integer.parseInt(value.split("/", 2)[0].trim())); }
		catch(NumberFormatException e) { return 0; }
	}

	public static int duration(String value) {
		try {
			double seconds = 0;
			for(String part : value.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
			return Double.isFinite(seconds) && seconds > 0 && seconds < Integer.MAX_VALUE ? (int) Math.round(seconds) : 0;
		} catch(NumberFormatException e) { return 0; }
	}

	/** Numeric filename runs sort naturally: t2 precedes t10. */
	public static int naturalCompare(String a, String b) {
		int i = 0, j = 0;
		while(i < a.length() && j < b.length()) {
			char ac = a.charAt(i), bc = b.charAt(j);
			if(Character.isDigit(ac) && Character.isDigit(bc)) {
				int ai = i, bj = j;
				while(i < a.length() && Character.isDigit(a.charAt(i))) i++;
				while(j < b.length() && Character.isDigit(b.charAt(j))) j++;
				java.math.BigInteger an = new java.math.BigInteger(a.substring(ai, i));
				java.math.BigInteger bn = new java.math.BigInteger(b.substring(bj, j));
				int result = an.compareTo(bn);
				if(result != 0) return result;
			} else {
				int result = Character.compare(Character.toLowerCase(ac), Character.toLowerCase(bc));
				if(result != 0) return result;
				i++; j++;
			}
		}
		return Integer.compare(a.length(), b.length());
	}

	private static String joinWith(String separator, List<String> values) {
		StringBuilder joined = new StringBuilder();
		for(String value : values) {
			if(joined.length() > 0) joined.append(separator);
			joined.append(value);
		}
		return joined.toString();
	}

	private static String label(String label, String value) {
		return value.isEmpty() ? "" : label + ": " + value;
	}

	public static String join(String... values) {
		List<String> parts = new ArrayList<>();
		for(String value : values) if(value != null && !value.isEmpty()) parts.add(value);
		return joinWith("\n", parts);
	}
}
