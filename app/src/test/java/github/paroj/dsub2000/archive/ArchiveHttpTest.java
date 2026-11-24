package github.paroj.dsub2000.archive;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.Assert.*;

public class ArchiveHttpTest {
	private static class Connection extends HttpURLConnection {
		final int code;
		String location;
		String range;
		String type = "audio/mpeg";
		boolean closed;
		byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String encoding;
		@Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(body); }
		@Override public String getContentEncoding() { return encoding; }
		Connection(int code) throws Exception { super(new URL("https://archive.org/test")); this.code = code; }
		@Override public int getResponseCode() { return code; }
		@Override public String getHeaderField(String name) { return name.equals("Location") ? location : name.equals("Content-Range") ? range : null; }
		@Override public String getContentType() { return type; }
		@Override public void disconnect() { closed = true; }
		@Override public boolean usingProxy() { return false; }
		@Override public void connect() {}
	}

	@Test public void rangeAndIdentityEncodingAreSetWithoutServerCredentials() throws Exception {
		Connection connection = new Connection(206); connection.range = "bytes 123-999/1000";
		assertSame(connection, ArchiveHttp.open("https://archive.org/download/show/t1.mp3", 123, url -> connection));
		assertEquals("bytes=123-", connection.getRequestProperty("Range"));
		assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
		assertNull(connection.getRequestProperty("Authorization"));
		assertFalse(connection.getInstanceFollowRedirects());
	}
	@Test public void ignoredRangeAllowsDownloaderToRestart() throws Exception {
		Connection connection = new Connection(200);
		assertSame(connection, ArchiveHttp.open("https://archive.org/download/show/t1.mp3", 123, url -> connection));
	}
	@Test public void malformedRangeIsRejectedAndClosed() throws Exception {
		Connection connection = new Connection(206); connection.range = "bytes 0-999/1000";
		try { ArchiveHttp.open("https://archive.org/download/show/t1.mp3", 123, url -> connection); fail(); }
		catch(IOException expected) { assertTrue(connection.closed); }
	}
	@Test public void storageNodeRedirectPreservesRange() throws Exception {
		Connection redirect = new Connection(302); redirect.location = "https://ia800000.us.archive.org/0/items/show/t1.mp3";
		Connection audio = new Connection(206); audio.range = "bytes 123-999/1000";
		Deque<Connection> connections = new ArrayDeque<>(); connections.add(redirect); connections.add(audio);
		ArchiveHttp.open("https://archive.org/download/show/t1.mp3", 123, url -> connections.remove());
		assertTrue(redirect.closed); assertEquals("bytes=123-", audio.getRequestProperty("Range"));
	}
	@Test public void foreignHostRedirectIsRejectedBeforeConnecting() throws Exception {
		Connection redirect = new Connection(302); redirect.location = "https://archive.org.evil.example/steal";
		int[] calls = {0};
		try { ArchiveHttp.open("https://archive.org/test", 0, url -> { calls[0]++; return redirect; }); fail(); }
		catch(IOException expected) { assertEquals(1, calls[0]); assertTrue(redirect.closed); }
	}
	@Test public void insecureRedirectIsRejected() throws Exception {
		Connection redirect = new Connection(302); redirect.location = "http://archive.org/test";
		try { ArchiveHttp.open("https://archive.org/test", 0, url -> redirect); fail(); }
		catch(IOException expected) { assertTrue(redirect.closed); }
	}
	@Test public void htmlErrorsAreNotSavedAsAudio() throws Exception {
		Connection connection = new Connection(200); connection.type = "text/html; charset=utf-8";
		try { ArchiveHttp.open("https://archive.org/test", 0, url -> connection); fail(); }
		catch(IOException expected) { assertTrue(connection.closed); }
	}
	@Test public void rateLimitIsAnErrorWithoutImmediateRetryStorm() throws Exception {
		Connection connection = new Connection(429); int[] calls = {0};
		try { ArchiveHttp.open("https://archive.org/test", 0, url -> { calls[0]++; return connection; }); fail(); }
		catch(IOException expected) { assertTrue(expected.getMessage().contains("429")); assertEquals(1, calls[0]); assertTrue(connection.closed); }
	}
	@Test public void missingFilesArePermanentFailures() throws Exception {
		try { ArchiveHttp.open("https://archive.org/test", 0, url -> { try { return new Connection(404); } catch(Exception e) { throw new IOException(e); } }); fail(); }
		catch(java.io.FileNotFoundException expected) { assertTrue(expected.getMessage().contains("unavailable")); }
	}

	@Test public void metadataUsesGzipButAudioRemainsByteExact() throws Exception {
		Connection c=new Connection(200);c.type="application/json";c.encoding="gzip";
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(out)) { gzip.write("{\"files\":[]}".getBytes("UTF-8")); }
		c.body=out.toByteArray();
		assertTrue(ArchiveHttp.json("https://archive.org/metadata/show",1024,url->c).has("files"));
		assertEquals("gzip",c.getRequestProperty("Accept-Encoding"));assertTrue(c.closed);
	}
	@Test public void decompressedMetadataStillHasASizeLimit() throws Exception {
		Connection c=new Connection(200);c.encoding="gzip";
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(out)) { gzip.write(new byte[4096]); }
		c.body=out.toByteArray();
		try { ArchiveHttp.json("https://archive.org/metadata/show",100,url->c);fail(); }
		catch(IOException expected) { assertTrue(expected.getMessage().contains("too large"));assertTrue(c.closed); }
	}
	@Test public void metadataTimeoutRetriesOnlyOnce() throws Exception {
		int[] calls={0};
		try { ArchiveHttp.json("https://archive.org/metadata/show",1024,url->{calls[0]++;throw new java.net.SocketTimeoutException();});fail(); }
		catch(java.net.SocketTimeoutException expected) { assertEquals(2,calls[0]); }
	}
	@Test public void metadataRateLimitDoesNotRetry() throws Exception {
		int[] calls={0};Connection c=new Connection(429);
		try { ArchiveHttp.json("https://archive.org/metadata/show",1024,url->{calls[0]++;return c;});fail(); }
		catch(IOException expected) { assertEquals(1,calls[0]); }
	}
	@Test public void knownStorageNodeAvoidsRedirectAndPreservesResume() throws Exception {
		ArchiveHttp.rememberLocation("node-test",new org.json.JSONObject().put("d1","ia800001.us.archive.org").put("dir","/4/items/node-test"));
		Connection c=new Connection(206);c.range="bytes 9-99/100";
		ArchiveHttp.download(ArchiveSource.trackId("node-test","a b.mp3"),9,url->{assertEquals("https://ia800001.us.archive.org/4/items/node-test/a%20b.mp3",url.toString());return c;});
		assertEquals("bytes=9-",c.getRequestProperty("Range"));
	}
	@Test public void movedNodeFallsBackToCanonicalResolver() throws Exception {
		ArchiveHttp.rememberLocation("moved-test",new org.json.JSONObject().put("d1","ia800001.us.archive.org").put("dir","/4/items/moved-test"));
		int[] calls={0};Connection old=new Connection(404), good=new Connection(200);
		ArchiveHttp.download(ArchiveSource.trackId("moved-test","t.mp3"),0,url->{calls[0]++;if(calls[0]==1)return old;assertEquals("archive.org",url.getHost());return good;});
		assertEquals(2,calls[0]);assertTrue(old.closed);
	}
	@Test public void untrustedMetadataLocationIsIgnored() throws Exception {
		ArchiveHttp.rememberLocation("unsafe-test",new org.json.JSONObject().put("d1","ia1.us.archive.org.evil.example").put("dir","/4/items/unsafe-test"));
		Connection good=new Connection(200);
		ArchiveHttp.download(ArchiveSource.trackId("unsafe-test","t.mp3"),0,url->{assertEquals("archive.org",url.getHost());return good;});
	}
}
