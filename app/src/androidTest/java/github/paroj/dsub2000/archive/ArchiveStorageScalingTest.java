package github.paroj.dsub2000.archive;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.service.DownloadFile;
import github.paroj.dsub2000.util.Constants;
import github.paroj.dsub2000.util.FileUtil;
import github.paroj.dsub2000.util.Util;
import static org.junit.Assert.*;

public class ArchiveStorageScalingTest {
	private Context context;
	private boolean offline;
	private String id;
	private final List<File> created=new ArrayList<>();
	@Before public void before() {
		context=InstrumentationRegistry.getInstrumentation().getTargetContext();offline=Util.isOffline(context);
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE,false).commit();
		id="scaling-test-"+UUID.randomUUID();
	}
	@After public void after() {
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE,offline).commit();
		for(File file:created) file.delete();
	}
	private JSONObject metadata() throws Exception {
		return new JSONObject().put("metadata",new JSONObject().put("creator",id).put("title","A long supplied title")
				.put("date","2026-10-31").put("coverage","Cambridge, MA").put("venue","The Sinclair"))
				.put("files",new JSONArray().put(new JSONObject().put("name","t1.mp3").put("title","First song").put("length","60").put("size","3")));
	}
	private void write(File file,String text) throws Exception {
		file.getParentFile().mkdirs();try(FileOutputStream output=new FileOutputStream(file)) { output.write(text.getBytes(StandardCharsets.UTF_8)); }created.add(file);
	}
	private JSONObject count(int n) throws Exception { return new JSONObject().put("response",new JSONObject().put("numFound",n).put("docs",new JSONArray())); }
	@Test public void hugeCollectionOnlyLoadsCountAndBounds() throws Exception {
		List<String> calls=new ArrayList<>();
		ArchiveClient client=new ArchiveClient(context,url->{
			calls.add(url);
			if(url.contains("rows=0")) return count(url.contains("-date") ? 0 : 500000);
			if(url.contains("rows=1")) return new JSONObject().put("response",new JSONObject().put("docs",new JSONArray().put(new JSONObject().put("date",url.contains("%20asc") ? "1965-01-01" : "1995-12-31"))));
			throw new AssertionError("Unnecessary bulk request: "+url);
		});
		List<ArchiveCatalog.Row> years=client.years(id,true,null);assertEquals(31,years.size());assertEquals(4,calls.size());
		for(String url:calls) { assertFalse(url.contains("/metadata/"));assertFalse(url.contains("scrape"));assertFalse(url.contains("/img/")); }
	}
	@Test public void hugeYearUsesBoundedLeapYearCalendarWithoutScraping() throws Exception {
		List<String> calls=new ArrayList<>();ArchiveClient client=new ArchiveClient(context,url->{calls.add(url);assertTrue(url.contains("rows=0"));return count(20000);});
		List<ArchiveCatalog.Row> dates=client.dates(id,"2024",true,null);assertEquals(366,dates.size());assertEquals("2024-02-29",dates.get(59).id);assertEquals(1,calls.size());
	}
	@Test public void smallYearFetchesOnlyItsDateFields() throws Exception {
		List<String> calls=new ArrayList<>();ArchiveClient client=new ArchiveClient(context,url->{
			calls.add(url);if(url.contains("rows=0")) return count(2);
			assertTrue(url.contains("2026-01-01"));assertTrue(url.contains("fields=identifier,date"));assertTrue(url.contains("count=500"));
			return new JSONObject().put("items",new JSONArray().put(new JSONObject().put("date","2026-10-31")).put(new JSONObject().put("date","2026-10-31")));
		});
		List<ArchiveCatalog.Row> rows=client.dates(id,"2026",true,null);assertEquals(1,rows.size());assertEquals(2,rows.get(0).count);assertEquals(2,calls.size());
	}
	@Test public void metadataMigratesOutOfDisposableCache() throws Exception {
		File old=new File(new File(context.getCacheDir(),"archive-catalog"),Util.md5Hex("https://archive.org/metadata/"+id)+".json");write(old,metadata().toString());
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE,true).commit();
		assertEquals(id,new ArchiveClient(context).recording(id,ArchiveCatalog.Format.MP3,false).id);assertTrue(old.delete());
		assertEquals(id,new ArchiveClient(context).recording(id,ArchiveCatalog.Format.MP3,false).id);
		File durable=new File(new File(context.getFilesDir(),"archive-catalog"),old.getName());assertTrue(durable.isFile());created.add(durable);
	}
	@Test public void downloadsRemainBrowsableWithoutAnyBrowseMetadata() throws Exception {
		ArchiveCatalog.Recording model=ArchiveCatalog.recording(id,metadata(),ArchiveCatalog.Format.MP3);
		ArchiveClient client=new ArchiveClient(context);Entry entry=client.entries(model).get(0);
		File file=FileUtil.getSongFile(context,entry);write(file,"mp3");
		ArchiveLibrary library=ArchiveLibrary.get(context);library.completed(new DownloadFile(context,entry,true));
		assertTrue(file.getAbsolutePath().contains("2026-10-31 Cambridge, MA - The Sinclair"+File.separator+id));
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE,true).commit();
		ArchiveCatalog.Recording saved=new ArchiveClient(context,url->{throw new AssertionError("Offline catalogue requested network");}).recording(id,ArchiveCatalog.Format.MP3,false,true);
		assertEquals(1,saved.tracks.size());assertEquals("First song",saved.tracks.get(0).title);
		assertEquals(1,library.recordings(id,"2026",1).rows.size());
		assertEquals(1,new github.paroj.dsub2000.service.OfflineMusicService().getMusicDirectory(entry.getParent(),"",false,context,null).getSongs().size());
		Entry tagged=library.entryForFile(file);assertNotNull(tagged);assertEquals(id,tagged.getArtist());assertEquals("2026-10-31 Cambridge, MA - The Sinclair",tagged.getAlbum());
		new DownloadFile(context,entry,true).unpin();assertEquals(0,library.recordings(id,"2026",1).total);
		created.add(new File(file.getParentFile(),Util.md5Hex(entry.getId())+".complete.mp3"));
	}
	@Test public void legacyDownloadFolderIsNotMovedOrDuplicated() throws Exception {
		File legacy=new File(FileUtil.getMusicDirectory(context),"Live Music Archive/"+id);assertTrue(legacy.mkdirs() || legacy.isDirectory());
		ArchiveClient client=new ArchiveClient(context);Entry entry=client.entries(ArchiveCatalog.recording(id,metadata(),ArchiveCatalog.Format.MP3)).get(0);
		assertEquals(legacy,FileUtil.getSongFile(context,entry).getParentFile());created.add(legacy);
	}
	@Test public void incompleteDownloadsAreNotInDownloadedCatalogue() throws Exception {
		ArchiveClient client=new ArchiveClient(context);Entry entry=client.entries(ArchiveCatalog.recording(id,metadata(),ArchiveCatalog.Format.MP3)).get(0);
		File partial=FileUtil.getSongFile(context,entry,"partial");write(partial,"partial");ArchiveLibrary library=ArchiveLibrary.get(context);library.completed(new DownloadFile(context,entry,true));
		assertEquals(0,library.recordings(id,"2026",1).total);
	}
	@Test public void serverPageCannotExceedSafetyLimit() throws Exception {
		ArchiveClient client=new ArchiveClient(context,url->{JSONArray docs=new JSONArray();for(int i=0;i<101;i++) docs.put(new JSONObject().put("identifier","id"+i));return new JSONObject().put("response",new JSONObject().put("numFound",10000).put("docs",docs));});
		try {client.collections("",1,true);fail("Oversized page accepted");} catch(java.io.IOException expected) {assertTrue(expected.getMessage().contains("page size"));}
	}
}
