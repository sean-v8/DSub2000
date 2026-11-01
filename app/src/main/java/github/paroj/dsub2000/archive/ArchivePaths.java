package github.paroj.dsub2000.archive;

import java.nio.charset.StandardCharsets;

/** Portable, byte-bounded components; the identifier remains a separate level. */
public final class ArchivePaths {
	public static final String ROOT = "Live Music Archive";
	private ArchivePaths() {}

	public static String component(String value, int maxBytes, String fallback) {
		String clean = value == null ? "" : value.replaceAll("<[^>]*>", "")
				.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").replaceAll("\\s+", " ").trim();
		while(clean.endsWith(".") || clean.endsWith(" ")) clean = clean.substring(0, clean.length() - 1);
		if(clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = fallback;
		StringBuilder result = new StringBuilder();
		int bytes = 0;
		for(int i = 0; i < clean.length();) {
			int cp = clean.codePointAt(i);
			String next = new String(Character.toChars(cp));
			int n = next.getBytes(StandardCharsets.UTF_8).length;
			if(bytes + n > maxBytes) break;
			result.append(next); bytes += n; i += Character.charCount(cp);
		}
		return result.length() == 0 ? fallback : result.toString().trim();
	}

	public static String album(ArchiveCatalog.Recording recording) {
		String event = recording.date.isEmpty() ? "Undated" : recording.date;
		if(recording.city != null && !recording.city.isEmpty()) event += " " + recording.city;
		if(recording.venue != null && !recording.venue.isEmpty()) event += " - " + recording.venue;
		return component(event, 112, "Undated");
	}

	public static String folder(ArchiveCatalog.Recording recording) {
		ArchiveSource.validateIdentifier(recording.id);
		return ROOT + "/" + component(recording.artist, 80, "Unknown artist")
				+ "/" + album(recording) + "/" + recording.id;
	}

	public static boolean validFolder(String folder, String identifier) {
		if(folder == null || !folder.startsWith(ROOT + "/") || !folder.endsWith("/" + identifier)) return false;
		for(String part : folder.split("/", -1)) {
			if(part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0) return false;
		}
		return true;
	}
}
