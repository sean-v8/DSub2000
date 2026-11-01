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
}
