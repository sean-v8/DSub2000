package github.paroj.dsub2000.archive;

import java.net.URLDecoder;
import java.net.URLEncoder;

/** Stable, namespaced identities; never contain Subsonic credentials. */
public final class ArchiveSource {
	public static final String PREFIX = "archive:";
	public static final String STREAM_PREFIX = "archive-stream:";

	private ArchiveSource() {}

	public static boolean isArchive(String id) {
		return id != null && (id.startsWith(PREFIX) || id.startsWith(STREAM_PREFIX));
	}

	public static boolean isStreamOnly(String id) {
		return id != null && id.startsWith(STREAM_PREFIX);
	}

	public static String identifier(String id) {
		if(!isArchive(id)) {
			throw new IllegalArgumentException("Not an Archive.org ID");
		}
		String value = id.substring(id.startsWith(STREAM_PREFIX) ? STREAM_PREFIX.length() : PREFIX.length()).split("/", 2)[0];
		validateIdentifier(value);
		return value;
	}

	public static void validateIdentifier(String value) {
		if(value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) {
			throw new IllegalArgumentException("Invalid Archive.org identifier");
		}
	}

	public static String itemId(String identifier) {
		validateIdentifier(identifier);
		return PREFIX + identifier;
	}

	public static String trackId(String identifier, String filename) {
		validateFilename(filename);
		return itemId(identifier) + "/" + encode(filename);
	}

	public static String filename(String id) {
		identifier(id);
		int slash = id.indexOf('/');
		if(slash < 0) {
			throw new IllegalArgumentException("Not an Archive.org track");
		}
		String filename;
		try { filename = URLDecoder.decode(id.substring(slash + 1), "UTF-8"); }
		catch(java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
		validateFilename(filename);
		return filename;
	}

	private static void validateFilename(String filename) {
		if(filename == null || filename.isEmpty() || filename.startsWith("/")
				|| filename.indexOf('\\') >= 0) {
			throw new IllegalArgumentException("Invalid Archive.org filename");
		}
		for(int i = 0; i < filename.length(); i++) {
			if(Character.isISOControl(filename.charAt(i))) throw new IllegalArgumentException("Invalid Archive.org filename");
		}
		for(String part : filename.split("/", -1)) {
			if(part.isEmpty() || part.equals(".") || part.equals("..")) {
				throw new IllegalArgumentException("Invalid Archive.org filename");
			}
		}
	}

	public static String downloadUrl(String id) {
		StringBuilder path = new StringBuilder("https://archive.org/download/")
				.append(identifier(id));
		for(String part : filename(id).split("/")) {
			path.append('/').append(encode(part));
		}
		return path.toString();
	}

	public static String detailsUrl(String id) {
		return "https://archive.org/details/" + identifier(id);
	}

	public static String artworkUrl(String id) {
		return "https://archive.org/services/img/" + identifier(id);
	}

	public static String encode(String value) {
		try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
		catch(java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
	}

	public static String quoted(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
