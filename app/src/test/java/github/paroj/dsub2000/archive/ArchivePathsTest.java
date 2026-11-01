package github.paroj.dsub2000.archive;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ArchivePathsTest {
	private ArchiveCatalog.Recording recording() {
		ArchiveCatalog.Recording r=new ArchiveCatalog.Recording();r.id="test2026";r.artist="Band";r.date="2026-10-31";r.city="Cambridge, MA";r.venue="The Sinclair";return r;
	}
	@Test public void dateCityVenueAndIdentifierAreSeparateComponents() {
		assertEquals("Live Music Archive/Band/2026-10-31 Cambridge, MA - The Sinclair/test2026",ArchivePaths.folder(recording()));
	}
	@Test public void sourceDoesNotLengthenFolder() {
		ArchiveCatalog.Recording r=recording();r.details="An extremely long microphone and recorder lineage";
		assertFalse(ArchivePaths.folder(r).contains("microphone"));
	}
	@Test public void everyNameIsBoundedInUtf8Bytes() {
		ArchiveCatalog.Recording r=recording();r.artist="音楽🎵".repeat(200);r.city="都市".repeat(200);r.venue="会場".repeat(200);
		String[] parts=ArchivePaths.folder(r).split("/");assertTrue(parts[1].getBytes(StandardCharsets.UTF_8).length<=80);assertTrue(parts[2].getBytes(StandardCharsets.UTF_8).length<=112);assertEquals("test2026",parts[3]);
	}
	@Test public void separatorsAndControlCharactersCannotCreateNewLevels() {
		ArchiveCatalog.Recording r=recording();r.artist="../Band\\Name\n";r.venue="A/B:C";assertEquals(4,ArchivePaths.folder(r).split("/").length);assertFalse(ArchivePaths.folder(r).contains("\\"));
	}
	@Test public void missingFieldsRemainReadable() {
		ArchiveCatalog.Recording r=recording();r.artist="";r.city="";r.venue="";r.date="";assertEquals("Live Music Archive/Unknown artist/Undated/test2026",ArchivePaths.folder(r));
	}
	@Test public void identifiersKeepTwoTapesOfOneShowDistinct() {
		ArchiveCatalog.Recording a=recording(),b=recording();b.id="other-tape";assertNotEquals(ArchivePaths.folder(a),ArchivePaths.folder(b));
	}
	@Test public void legacyFolderIsStillValid() { assertTrue(ArchivePaths.validFolder("Live Music Archive/test2026","test2026")); }
	@Test public void traversalIsRejected() { assertFalse(ArchivePaths.validFolder("Live Music Archive/../test2026","test2026")); }
}
