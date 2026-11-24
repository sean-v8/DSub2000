package github.paroj.dsub2000.archive;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.util.zip.GZIPInputStream;

/** Archive requests never pass through the authenticated Subsonic HTTP client. */
public final class ArchiveHttp {
	private ArchiveHttp() {}

	private static final java.util.LinkedHashMap<String, String> NODES = new java.util.LinkedHashMap<>();

	/** Reuse the location supplied by the item metadata, avoiding a redirect. */
	static synchronized void rememberLocation(String id, JSONObject metadata) {
		String host=metadata.optString("d1"), directory=metadata.optString("dir");
		if(!host.matches("ia[0-9]+\\.[a-z]+\\.archive\\.org")
				|| !directory.matches("/[0-9]+/items/"+java.util.regex.Pattern.quote(id))) return;
		NODES.remove(id);NODES.put(id,"https://"+host+directory);
		while(NODES.size()>256) NODES.remove(NODES.keySet().iterator().next());
	}

	public static HttpURLConnection download(String id,long offset) throws IOException {
		return download(id,offset,target -> (HttpURLConnection)target.openConnection());
	}

	static HttpURLConnection download(String id,long offset,ConnectionFactory factory) throws IOException {
		String identifier=ArchiveSource.identifier(id), base;
		synchronized(ArchiveHttp.class) { base=NODES.get(identifier); }
		if(base!=null) {
			StringBuilder url=new StringBuilder(base);
			for(String part:ArchiveSource.filename(id).split("/")) url.append('/').append(ArchiveSource.encode(part));
			try { return open(url.toString(),offset,factory); }
			catch(IOException error) {
				if(Thread.currentThread().isInterrupted() || error instanceof java.io.InterruptedIOException && !(error instanceof SocketTimeoutException)
						|| error instanceof StatusException && ((StatusException)error).code==429) throw error;
				// Items can move storage nodes. Let the canonical URL resolve again.
				synchronized(ArchiveHttp.class) { NODES.remove(identifier); }
			}
		}
		return open(ArchiveSource.downloadUrl(id),offset,factory);
	}

	private static final class StatusException extends IOException {
		final int code;
		StatusException(int code) { super("Archive.org HTTP " + code + (code==429 ? ": please try again later" : "")); this.code=code; }
	}

	interface ConnectionFactory {
		HttpURLConnection open(URL url) throws IOException;
	}

	public static HttpURLConnection open(String url, long offset) throws IOException {
		return open(url, offset, target -> (HttpURLConnection) target.openConnection());
	}

	static HttpURLConnection open(String url, long offset, ConnectionFactory factory) throws IOException {
		return open(url,offset,factory,false);
	}

	private static HttpURLConnection open(String url,long offset,ConnectionFactory factory,boolean metadata) throws IOException {
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
			connection.setConnectTimeout(metadata ? 10000 : 15000);
			connection.setReadTimeout(metadata ? 15000 : 30000);
			connection.setRequestProperty("User-Agent", "DSub2000/Archive (https://github.com/sean-v8/DSub2000)");
			// Range offsets must refer to the actual audio file bytes.
			connection.setRequestProperty("Accept-Encoding", metadata ? "gzip" : "identity");
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
					throw new StatusException(code);
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
		return json(url,maxBytes,target -> (HttpURLConnection)target.openConnection());
	}

	static JSONObject json(String url,int maxBytes,ConnectionFactory factory) throws Exception {
		// Retry one transient timeout, never permanent/rate-limit/parse failures.
		for(int attempt=0;;attempt++) {
			try { return readJson(url,maxBytes,factory); }
			catch(SocketTimeoutException timeout) {
				if(attempt>0 || Thread.currentThread().isInterrupted()) throw timeout;
			}
		}
	}

	private static JSONObject readJson(String url,int maxBytes,ConnectionFactory factory) throws Exception {
		HttpURLConnection connection = open(url, 0, factory, true);
		try(InputStream raw = connection.getInputStream();
				InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
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
