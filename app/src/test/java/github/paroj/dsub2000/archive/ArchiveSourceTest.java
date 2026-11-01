package github.paroj.dsub2000.archive;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArchiveSourceTest {
	@Test public void unicodeSpacesAndReservedCharactersRoundTrip() {
		String name = "set 1/01 café + #?.mp3";
		String id = ArchiveSource.trackId("band2026-10-03", name);
		assertEquals(name, ArchiveSource.filename(id));
		assertEquals("https://archive.org/download/band2026-10-03/set%201/01%20caf%C3%A9%20%2B%20%23%3F.mp3", ArchiveSource.downloadUrl(id));
	}
	@Test public void literalPercentIsNotDoubleDecoded() {
		String name = "%2e%2e.mp3";
		assertEquals(name, ArchiveSource.filename(ArchiveSource.trackId("show1", name)));
		assertTrue(ArchiveSource.downloadUrl(ArchiveSource.trackId("show1", name)).endsWith("%252e%252e.mp3"));
	}
	@Test public void serverIdsAreNotArchiveIds() {
		assertFalse(ArchiveSource.isArchive("1234"));
		assertFalse(ArchiveSource.isArchive(null));
		assertFalse(ArchiveSource.isArchive("https://archive.org/foo"));
	}
	@Test public void restrictedIdsKeepTheirIdentity() {
		String id = ArchiveSource.trackId("show1", "track.mp3").replace(ArchiveSource.PREFIX, ArchiveSource.STREAM_PREFIX);
		assertTrue(ArchiveSource.isArchive(id));
		assertTrue(ArchiveSource.isStreamOnly(id));
		assertEquals("show1", ArchiveSource.identifier(id));
		assertTrue(ArchiveSource.downloadUrl(id).endsWith("show1/track.mp3"));
	}
	@Test(expected = IllegalArgumentException.class) public void traversalRejected() { ArchiveSource.trackId("show1", "../track.mp3"); }
	@Test(expected = IllegalArgumentException.class) public void nestedTraversalRejected() { ArchiveSource.trackId("show1", "set1/../track.mp3"); }
	@Test(expected = IllegalArgumentException.class) public void identifierCannotContainQuery() { ArchiveSource.itemId("show1?token=x"); }
	@Test(expected = IllegalArgumentException.class) public void identifierCannotContainPath() { ArchiveSource.itemId("show1/other"); }
	@Test(expected = IllegalArgumentException.class) public void nonArchiveIdentifierRejected() { ArchiveSource.identifier("123"); }
	@Test(expected = IllegalArgumentException.class) public void itemCannotBeUsedAsTrack() { ArchiveSource.downloadUrl("archive:show1"); }
	@Test(expected = IllegalArgumentException.class) public void controlCharactersRejected() { ArchiveSource.trackId("show1", "a\nb\nc.mp3"); }
	@Test public void queryLiteralsAreEscaped() { assertEquals("\"a\\\" OR collection:x\"", ArchiveSource.quoted("a\" OR collection:x")); }

	@Test public void alphabeticalGroupsUseBoundedPrefixQueries() {
		assertEquals("titleSorter:a*",ArchiveSource.collectionGroupQuery("A"));
		assertEquals("titleSorter:z*",ArchiveSource.collectionGroupQuery("Z"));
		assertTrue(ArchiveSource.collectionGroupQuery("#").contains("titleSorter:z*"));
		assertTrue(ArchiveSource.collectionGroupQuery("#").startsWith("NOT ("));
	}
	@Test(expected=IllegalArgumentException.class) public void groupCannotEscapeSupportedRoots() {
		ArchiveSource.collectionGroupQuery("A OR *:*");
	}
}
