package github.paroj.dsub2000.archive;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ArchiveCatalogTest {
	private JSONObject file(String name, String original, String track) throws Exception {
		JSONObject file = new JSONObject().put("name", name).put("track", track)
				.put("title", name).put("length", "1:02.5").put("size", "123456");
		if(original != null) file.put("original", original);
		return file;
	}

	private JSONObject item(JSONObject... files) throws Exception {
		JSONArray array = new JSONArray();
		for(JSONObject file : files) array.put(file);
		return new JSONObject().put("metadata", new JSONObject().put("creator", "Band")
				.put("date", "2026-10-03T00:00:00Z").put("title", "Band at Venue"))
				.put("files", array);
	}

	@Test public void mp3AndFlacDerivativesAreOneTrack() throws Exception {
		JSONObject json = item(file("t01.flac", null, "1"), file("t01.mp3", "t01.flac", "1"),
				file("t01_vbr.mp3", "t01.flac", "1").put("format", "VBR MP3"));
		ArchiveCatalog.Recording mp3 = ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.MP3);
		assertEquals(1, mp3.tracks.size());
		assertEquals("t01_vbr.mp3", mp3.tracks.get(0).filename);
		assertEquals("t01.flac", ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.FLAC).tracks.get(0).filename);
	}

	@Test public void fallbackWorksPerTrack() throws Exception {
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("band2026", item(
				file("t01.flac", null, "1"), file("t01.mp3", "t01.flac", "1"), file("t02.mp3", null, "2")), ArchiveCatalog.Format.FLAC);
		assertEquals(2, result.tracks.size());
		assertEquals("flac", result.tracks.get(0).suffix);
		assertEquals("mp3", result.tracks.get(1).suffix);
	}

	@Test public void followsOriginalChains() throws Exception {
		JSONObject json = item(file("t01.flac", null, "1"), file("t01.ogg", "t01.flac", "1"), file("t01.mp3", "t01.ogg", "1"));
		assertEquals(1, ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.MP3).tracks.size());
	}

	@Test public void absentOriginalLinksStillDeduplicate() throws Exception {
		assertEquals(1, ArchiveCatalog.recording("band2026", item(file("t1.mp3", null, ""), file("t1.flac", null, "")), ArchiveCatalog.Format.MP3).tracks.size());
	}

	@Test public void naturalOrderWhenTagsMissing() throws Exception {
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("band2026", item(file("t10.mp3", null, ""), file("t2.mp3", null, ""), file("t1.mp3", null, "")), ArchiveCatalog.Format.MP3);
		assertEquals("t1.mp3", result.tracks.get(0).filename);
		assertEquals("t2.mp3", result.tracks.get(1).filename);
		assertEquals("t10.mp3", result.tracks.get(2).filename);
	}

	@Test public void missingTagsDoNotBreakComparatorContract() throws Exception {
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("band2026", item(file("z.mp3", null, "1"), file("a.mp3", null, "2"), file("m.mp3", null, "")), ArchiveCatalog.Format.MP3);
		assertEquals("a.mp3", result.tracks.get(0).filename);
		assertEquals("m.mp3", result.tracks.get(1).filename);
	}

	@Test public void discComesBeforeTrackIncludingFilenameDiscs() throws Exception {
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("band2026", item(file("band.d2t01.mp3", null, "1"), file("band.d1t02.mp3", null, "2"), file("band.d1t01.mp3", null, "1")), ArchiveCatalog.Format.MP3);
		assertEquals("band.d1t01.mp3", result.tracks.get(0).filename);
		assertEquals("band.d1t02.mp3", result.tracks.get(1).filename);
		assertEquals(2, result.tracks.get(2).disc);
	}

	@Test public void privateAndNonAudioFilesExcluded() throws Exception {
		JSONObject json = item(file("private.mp3", null, "1").put("private", "true"), file("notes.txt", null, ""), file("show.afpk", null, ""), file("song.FLAC", null, "2"));
		assertEquals(1, ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.FLAC).tracks.size());
	}

	@Test public void streamOnlyNeverSelectsLossless() throws Exception {
		JSONObject json = item(file("t01.flac", null, "1"), file("t01.mp3", "t01.flac", "1"));
		json.getJSONObject("metadata").put("stream_only", "true");
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.FLAC);
		assertTrue(result.streamOnly);
		assertEquals("mp3", result.tracks.get(0).suffix);
	}

	@Test public void invalidPathsAreNotPlayable() throws Exception {
		JSONObject json = item(file("../escape.mp3", null, "1"), file("/absolute.mp3", null, "2"), file("safe.mp3", null, "3"));
		assertEquals(1, ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.MP3).tracks.size());
	}

	@Test public void durationsAcceptDecimalAndColonFormats() {
		assertEquals(63, ArchiveCatalog.duration("1:02.5"));
		assertEquals(3601, ArchiveCatalog.duration("1:00:01"));
		assertEquals(0, ArchiveCatalog.duration("NaN"));
		assertEquals(0, ArchiveCatalog.duration("-3"));
		assertEquals(0, ArchiveCatalog.duration(""));
	}

	@Test public void datesAreStrictAndTimezoneSuffixIsIgnored() {
		assertEquals("2024-02-29", ArchiveCatalog.date("2024-02-29T00:00:00Z"));
		assertEquals("", ArchiveCatalog.date("2023-02-29"));
		assertEquals("", ArchiveCatalog.date("2026-13-01"));
		assertEquals("", ArchiveCatalog.date("2026"));
	}

	@Test public void scalarAndArrayMetadataAreSupported() throws Exception {
		JSONObject json = item(file("t01.mp3", null, "1"));
		json.getJSONObject("metadata").put("creator", new JSONArray().put("Band").put("Guest"));
		assertEquals("Band; Guest", ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.MP3).artist);
	}

	@Test public void metadataDefaultsDoNotCrash() throws Exception {
		JSONObject json = new JSONObject().put("metadata", new JSONObject()).put("files", new JSONArray());
		assertEquals("band2026", ArchiveCatalog.recording("band2026", json, ArchiveCatalog.Format.MP3).title);
	}

	@Test public void timelineCountsAndOrdersYearsAndDates() {
		Map<String, Integer> dates = new LinkedHashMap<>();
		dates.put("2025-03-01", 1); dates.put("2026-03-01", 3); dates.put("2026-01-01", 2);
		assertEquals("2026", ArchiveCatalog.years(dates).get(0).id);
		assertEquals(5, ArchiveCatalog.years(dates).get(0).count);
		assertEquals("2026-01-01", ArchiveCatalog.dates(dates, "2026").get(0).id);
	}

	@Test public void searchRowsDeduplicateIdentifiers() throws Exception {
		JSONArray docs = new JSONArray().put(new JSONObject().put("identifier", "show1").put("title", "Show"))
				.put(new JSONObject().put("identifier", "show1").put("title", "Show"));
		assertEquals(1, ArchiveCatalog.searchRows(new JSONObject().put("response", new JSONObject().put("docs", docs))).size());
	}

	@Test public void naturalOrderHandlesLongNumberRuns() {
		assertTrue(ArchiveCatalog.naturalCompare("t9999999999999999999999", "t10000000000000000000000") < 0);
	}

	@Test public void stableIdentifiersDifferAcrossRecordings() throws Exception {
		assertNotEquals(ArchiveCatalog.recording("show1", item(file("t1.mp3", null, "1")), ArchiveCatalog.Format.MP3).tracks.get(0).id,
				ArchiveCatalog.recording("show2", item(file("t1.mp3", null, "1")), ArchiveCatalog.Format.MP3).tracks.get(0).id);
	}
	@Test public void derivativesInheritMissingTagsFromOriginals() throws Exception {
		JSONObject original = file("z.flac", null, "1").put("title", "Opening song");
		JSONObject derivative = new JSONObject().put("name", "z.mp3").put("original", "z.flac").put("size", "100");
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("show1", item(original, derivative, file("a.mp3", null, "2")), ArchiveCatalog.Format.MP3);
		assertEquals("Opening song", result.tracks.get(0).title);
		assertEquals(100, result.tracks.get(0).size);
		assertEquals(63, result.tracks.get(0).duration);
	}

	@Test public void noDownloadFlagAlsoForcesStreamFormat() throws Exception {
		JSONObject json = item(file("t1.flac", null, "1"), file("t1.mp3", "t1.flac", "1"));
		json.getJSONObject("metadata").put("no-download", "1");
		ArchiveCatalog.Recording result = ArchiveCatalog.recording("show1", json, ArchiveCatalog.Format.FLAC);
		assertTrue(result.streamOnly);
		assertEquals("mp3", result.tracks.get(0).suffix);
	}

	@Test(expected=org.json.JSONException.class) public void oversizedFileListsAreRejectedBeforeSelection() throws Exception {
		JSONArray files=new JSONArray();for(int i=0;i<5001;i++) files.put(new JSONObject().put("name","notes"+i+".txt"));
		ArchiveCatalog.recording("show1",new JSONObject().put("metadata",new JSONObject()).put("files",files),ArchiveCatalog.Format.MP3);
	}
	@Test(expected=org.json.JSONException.class) public void oversizedTrackListsAreNotSilentlyTruncated() throws Exception {
		JSONArray files=new JSONArray();for(int i=0;i<501;i++) files.put(new JSONObject().put("name","t"+i+".mp3"));
		ArchiveCatalog.recording("show1",new JSONObject().put("metadata",new JSONObject()).put("files",files),ArchiveCatalog.Format.MP3);
	}
	@Test public void browserLabelsHaveBoundedLength() throws Exception {
		JSONArray docs=new JSONArray().put(new JSONObject().put("identifier","show1").put("title","x".repeat(10000)));
		assertTrue(ArchiveCatalog.searchRows(new JSONObject().put("response",new JSONObject().put("docs",docs))).get(0).title.length()<=513);
	}

}
