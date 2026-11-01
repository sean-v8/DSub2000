package github.paroj.dsub2000.archive;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Archive requests never pass through the authenticated Subsonic HTTP client. */
public final class ArchiveHttp {
	private ArchiveHttp() {}

	interface ConnectionFactory {
		HttpURLConnection open(URL url) throws IOException;
	}

	public static HttpURLConnection open(String url, long offset) throws IOException {
		return open(url, offset, target -> (HttpURLConnection) target.openConnection());
	}

	static HttpURLConnection open(String url, long offset, ConnectionFactory factory) throws IOException {
		URL target = new URL(url);
		for(int redirects = 0; redirects < 8; redirects++) {
			String host = target.getHost();
			if(!target.getProtocol().equals("https")
					|| !(host.equals("archive.org") || host.endsWith(".archive.org"))) {
				throw new IOException("Unexpected Archive.org redirect");
			}
			if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
			HttpURLConnection connection = factory.open(target);
			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(15000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("User-Agent", "DSub2000/Archive (https://github.com/sean-v8/DSub2000)");
			// Range offsets must refer to the actual audio file bytes.
			connection.setRequestProperty("Accept-Encoding", "identity");
			if(offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
			try {
				int code = connection.getResponseCode();
				if(code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
					String location = connection.getHeaderField("Location");
					if(location == null) throw new IOException("Missing Archive.org redirect");
					target = new URL(target, location);
					connection.disconnect();
					continue;
				}
				if(code == 404) throw new FileNotFoundException("Archive.org item or file is unavailable");
				if(code != 200 && code != 206) {
					throw new IOException("Archive.org HTTP " + code + (code == 429 ? ": please try again later" : ""));
				}
				if(code == 206) {
					String range = connection.getHeaderField("Content-Range");
					if(range == null || !range.startsWith("bytes " + offset + "-")) {
						throw new IOException("Invalid Archive.org partial response");
					}
				}
				String type = connection.getContentType();
				if(type != null && type.toLowerCase(java.util.Locale.US).contains("text/html")) {
					throw new IOException("Archive.org returned a web page instead of media or metadata");
				}
				return connection;
			} catch(IOException e) {
				connection.disconnect();
				throw e;
			}
		}
		throw new IOException("Too many Archive.org redirects");
	}

	public static JSONObject json(String url) throws Exception {
		return json(url,32 * 1024 * 1024);
	}

	public static JSONObject json(String url, int maxBytes) throws Exception {
		HttpURLConnection connection = open(url, 0);
		try(InputStream input = connection.getInputStream();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[16384];
			int n;
			while((n = input.read(buffer)) != -1) {
				if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
				if(output.size() + n > maxBytes) throw new IOException("Archive.org response is too large");
				output.write(buffer, 0, n);
			}
			JSONObject json = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
			if(json.has("error")) throw new IOException("Archive.org: " + json.optString("error"));
			return json;
		} finally {
			connection.disconnect();
		}
	}
}
