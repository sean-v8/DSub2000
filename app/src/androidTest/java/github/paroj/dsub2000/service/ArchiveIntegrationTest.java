package github.paroj.dsub2000.service;

import android.content.Context;
import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import github.paroj.dsub2000.R;
import github.paroj.dsub2000.activity.SubsonicFragmentActivity;
import github.paroj.dsub2000.archive.ArchiveCatalog;
import github.paroj.dsub2000.archive.ArchiveClient;
import github.paroj.dsub2000.archive.ArchiveSource;
import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.fragments.ArchiveFragment;
import github.paroj.dsub2000.util.Constants;
import github.paroj.dsub2000.util.FileUtil;
import github.paroj.dsub2000.util.Util;

import static org.junit.Assert.*;

/** Offline fixtures exercise the real Android models, cache, UI and Media3 resolver. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ArchiveIntegrationTest {
	@Rule public ActivityTestRule<SubsonicFragmentActivity> activity = new ActivityTestRule<>(SubsonicFragmentActivity.class, false, false);
	private Context context;
	private android.content.ServiceConnection connection;
	private boolean wasOffline;
	private String identifier;
	private File metadata;
	private File timeline;
	private JSONObject item;

	@Before public void setUp() throws Exception {
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		wasOffline = Util.isOffline(context);
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE, true).commit();

		identifier = "archive-test-" + UUID.randomUUID();
		item = new JSONObject().put("metadata", new JSONObject().put("title", "A test recording")
				.put("creator", new JSONArray().put("Band").put("Guest")).put("date", "2026-10-03"))
				.put("files", new JSONArray().put(new JSONObject().put("name", "t02.mp3").put("track", "2").put("length", "2:00").put("size", "48000"))
						.put(new JSONObject().put("name", "t01.mp3").put("track", "1").put("length", "1:00").put("size", "24000")));
		File cache = new File(context.getCacheDir(), "archive-catalog");
		assertTrue(cache.isDirectory() || cache.mkdirs());
		metadata = new File(cache, Util.md5Hex("https://archive.org/metadata/" + identifier) + ".json");
		timeline = new File(cache, Util.md5Hex("timeline-v1:" + identifier) + ".json");
		write(metadata, item);
		write(timeline, new JSONObject().put("2026-10-03", 2).put("2025-01-01", 1));
	}

	@After public void tearDown() {
		Util.getPreferences(context).edit().putBoolean(Constants.PREFERENCES_KEY_OFFLINE, wasOffline).commit();
		if(metadata != null) metadata.delete();
		if(timeline != null) timeline.delete();
		if(connection != null) context.unbindService(connection);
	}

	private DownloadService service() throws Exception {
		// Binding avoids the foreground-start deadline during cold verification
		// on software emulators. Pure metadata tests need no Activity or service.
		if(connection == null) {
			java.util.concurrent.CountDownLatch bound = new java.util.concurrent.CountDownLatch(1);
			connection = new android.content.ServiceConnection() {
				@Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) { bound.countDown(); }
				@Override public void onServiceDisconnected(android.content.ComponentName name) {}
			};
			assertTrue(context.bindService(new android.content.Intent(context, DownloadService.class), connection, Context.BIND_AUTO_CREATE));
			assertTrue("Playback service bound", bound.await(90, TimeUnit.SECONDS));
		}
		assertNotNull(DownloadService.getInstance());
		return DownloadService.getInstance();
	}

	private void write(File file, JSONObject json) throws Exception {
		try(FileOutputStream output = new FileOutputStream(file)) { output.write(json.toString().getBytes(StandardCharsets.UTF_8)); }
	}

	@Test public void cachedMetadataAndTimelineWorkWithoutServerOrNetwork() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		assertEquals(2, client.timeline(identifier, false, null).get("2026-10-03").intValue());
		ArchiveCatalog.Recording recording = client.recording(identifier, ArchiveCatalog.Format.MP3, false);
		assertTrue(client.isCached());
		List<Entry> entries = client.entries(recording);
		assertEquals(2, entries.size());
		assertEquals("Band; Guest", entries.get(0).getArtist());
		assertEquals(60, entries.get(0).getDuration().intValue());
		assertEquals(ArchiveSource.itemId(identifier), entries.get(0).getAlbumId());
		assertEquals("mp3", entries.get(0).getSuffix());
	}

	@Test public void existingEntrySerializationPreservesSourceAndFormat() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		Entry original = client.entries(client.recording(identifier, ArchiveCatalog.Format.MP3, false)).get(0);
		Entry copy = Entry.fromByteArray(original.toByteArray());
		assertEquals(original, copy);
		assertEquals(original.getCoverArt(), copy.getCoverArt());
		assertEquals(ArchiveSource.downloadUrl(original.getId()), ArchiveSource.downloadUrl(copy.getId()));
	}

	@Test public void cacheFilesDifferBetweenTapesAndBetweenFormats() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		Entry entry = client.entries(client.recording(identifier, ArchiveCatalog.Format.MP3, false)).get(0);
		File first = FileUtil.getSongFiles(context, entry).save();
		Entry other = Entry.fromByteArray(entry.toByteArray());
		other.setId(ArchiveSource.trackId(identifier + "-other", "t01.mp3"));
		assertNotEquals(first, FileUtil.getSongFiles(context, other).save());
		other.setId(ArchiveSource.trackId(identifier, "t01.flac"));
		other.setSuffix("flac");
		assertNotEquals(first, FileUtil.getSongFiles(context, other).save());
		assertEquals(identifier, first.getParentFile().getName());
		assertTrue(first.getAbsolutePath().contains("Live Music Archive" + File.separator + "Band; Guest" + File.separator + "2026-10-03" + File.separator));
	}

	@Test public void batchCachePathsMatchIndividualArchivePaths() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		Entry entry = client.entries(client.recording(identifier, ArchiveCatalog.Format.MP3, false)).get(0);
		FileUtil.SongFiles individual = FileUtil.getSongFiles(context, entry);
		FileUtil.SongFiles batch = new FileUtil.SongFileBatch(context).get(entry);
		assertEquals(individual, batch);
		String base = github.paroj.dsub2000.util.Util.md5Hex(entry.getId());
		assertEquals(base + ".mp3", batch.save().getName());
		assertEquals(base + ".partial.mp3", batch.partial().getName());
		assertEquals(base + ".complete.mp3", batch.complete().getName());
		assertEquals(batch.save(), new DownloadFile(context, entry, true, batch).getSaveFile());
	}

	@Test public void streamOnlyRestrictionSurvivesQueueSerialization() throws Exception {
		item.getJSONObject("metadata").put("stream_only", true);
		write(metadata, item);
		ArchiveClient client = new ArchiveClient(context);
		Entry entry = client.entries(client.recording(identifier, ArchiveCatalog.Format.FLAC, false)).get(0);
		assertTrue(ArchiveSource.isStreamOnly(Entry.fromByteArray(entry.toByteArray()).getId()));
	}

	@Test public void sessionBrowserExposesArchiveAndCachedYears() throws Exception {
		SessionLibrary library = new SessionLibrary(service());
		try {
			List<MediaItem> root = library.children(SessionLibrary.ROOT, 0, 100).get(10, TimeUnit.SECONDS);
			boolean found = false;
			for(MediaItem child : root) if(child.mediaId.equals("ia-root")) found = true;
			assertTrue(found);
			List<MediaItem> years = library.children("ia-years-" + identifier, 0, 100).get(10, TimeUnit.SECONDS);
			assertEquals(2, years.size());
			assertEquals("2026", years.get(0).mediaMetadata.title.toString());
		} finally { library.release(); }
	}

	@Test public void sessionSelectionResolvesArchiveAlbumWithoutSubsonicRequest() throws Exception {
		DownloadService service = service();
		SessionLibrary library = new SessionLibrary(service);
		SessionQueueResolver resolver = new SessionQueueResolver(service, library);
		try {
			List<MediaItem> children = library.children("ia-tracks-" + identifier, 0, 100).get(10, TimeUnit.SECONDS);
			MediaItem selected = children.get(children.size() - 1);
			MediaItemsWithStartPosition queue = resolver.resolveRequest(new OfflineMusicService(), selected);
			assertEquals(2, queue.mediaItems.size());
			assertEquals(1, queue.startIndex);
			assertTrue(ArchiveSource.isArchive(queue.mediaItems.get(0).mediaId));
		} finally { resolver.release(); library.release(); }
	}

	@Test public void bareArchiveMediaIdResolvesAfterBrowserCacheEviction() throws Exception {
		DownloadService service = service();
		SessionLibrary library = new SessionLibrary(service);
		SessionQueueResolver resolver = new SessionQueueResolver(service, library);
		try {
			String id = ArchiveSource.trackId(identifier, "t02.mp3");
			MediaItemsWithStartPosition queue = resolver.resolveRequest(new OfflineMusicService(), new MediaItem.Builder().setMediaId(id).build());
			assertEquals(2, queue.mediaItems.size());
			assertEquals(1, queue.startIndex);
		} finally { resolver.release(); library.release(); }
	}

	@Test public void cachedArchiveTrackPlaysAndSeeksThroughExistingPlayer() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		Entry entry = client.entries(client.recording(identifier, ArchiveCatalog.Format.MP3, false)).get(0);
		DownloadService service = service();
		File audio = FileUtil.getSongFiles(context, entry).save();
		assertTrue(audio.getParentFile().isDirectory() || audio.getParentFile().mkdirs());
		try(java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("archive-tone.mp3");
				FileOutputStream output = new FileOutputStream(audio)) {
			byte[] bytes = new byte[4096];
			int n;
			while((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
		}
		try {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				service.clear();
				service.download(java.util.Collections.singletonList(entry), false, true, false, false);
			});
			long deadline = System.currentTimeMillis() + 20000;
			while(service.getPlayerState() != github.paroj.dsub2000.domain.PlayerState.STARTED && System.currentTimeMillis() < deadline) Thread.sleep(50);
			assertEquals(github.paroj.dsub2000.domain.PlayerState.STARTED, service.getPlayerState());
			InstrumentationRegistry.getInstrumentation().runOnMainSync(service::pause);
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> service.seekTo(1000));
			// Seeking a complete proxy-backed file re-prepares asynchronously.
			// A paused player also prevents a short fixture ending during polling.
			deadline = System.currentTimeMillis() + 15000;
			while((service.getPlayerState() != github.paroj.dsub2000.domain.PlayerState.PAUSED
					|| service.getPlayerPosition() < 900) && System.currentTimeMillis() < deadline) Thread.sleep(50);
			assertEquals(github.paroj.dsub2000.domain.PlayerState.PAUSED, service.getPlayerState());
			assertTrue("Finite Archive track supports seeking", service.getPlayerPosition() >= 900 && service.getPlayerPosition() <= 1500);
		} finally {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(service::clear);
			audio.delete();
		}
	}

	@Test public void downloaderArtworkSizeZeroUsesCachedArtWithoutNetwork() throws Exception {
		ArchiveClient client = new ArchiveClient(context);
		Entry entry = client.entries(client.recording(identifier, ArchiveCatalog.Format.MP3, false)).get(0);
		File file = FileUtil.getAlbumArtFile(context, entry);
		assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
		android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
		try(FileOutputStream output = new FileOutputStream(file)) { image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output); }
		image.recycle();
		try {
			android.graphics.Bitmap cached = new CachedMusicService(new RESTMusicService()).getCoverArt(context, entry, 0, null, null);
			assertNotNull(cached);
			assertTrue(cached.getWidth() > 0);
			cached.recycle();
		} finally { file.delete(); }
	}

	@Test public void recordingScreenLoadsCachedTrackList() throws Exception {
		service();
		activity.launchActivity(new android.content.Intent(context, SubsonicFragmentActivity.class));
		ArchiveFragment fragment = ArchiveFragment.recording(ArchiveSource.itemId(identifier));
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
				activity.getActivity().replaceFragment(fragment, fragment.getSupportTag(), false));
		long deadline = System.currentTimeMillis() + 10000;
		AtomicReference<Integer> count = new AtomicReference<>(0);
		do {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				ListView list = activity.getActivity().findViewById(R.id.archive_list);
				count.set(list == null ? 0 : list.getCount());
			});
			if(count.get() == 2) break;
			Thread.sleep(50);
		} while(System.currentTimeMillis() < deadline);
		assertEquals(2, count.get().intValue());
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TextView status = activity.getActivity().findViewById(R.id.archive_status);
			assertTrue(status.getText().toString().contains(context.getString(R.string.archive_cached)));
			assertTrue(activity.getActivity().findViewById(R.id.archive_play).isEnabled());
		});
	}

	@Test public void alphabeticalLandingNeedsNoNetworkAndSessionMatches() throws Exception {
		DownloadService service=service();
		SessionLibrary library=new SessionLibrary(service);
		try {
			List<MediaItem> groups=library.children("ia-groups",0,100).get(10,TimeUnit.SECONDS);
			assertEquals(27,groups.size());assertEquals("A",groups.get(0).mediaMetadata.title.toString());
			assertEquals("Z",groups.get(25).mediaMetadata.title.toString());
		} finally { library.release(); }
		activity.launchActivity(new android.content.Intent(context,SubsonicFragmentActivity.class));
		ArchiveFragment fragment=new ArchiveFragment();
		InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.getActivity().replaceFragment(fragment,fragment.getSupportTag(),false));
		long deadline=System.currentTimeMillis()+10000;AtomicReference<Integer> count=new AtomicReference<>(0);
		do {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
				ListView list=activity.getActivity().findViewById(R.id.archive_list);count.set(list==null ? 0 : list.getCount());
			});
			if(count.get()==28)break;Thread.sleep(50);
		} while(System.currentTimeMillis()<deadline);
		assertEquals(28,count.get().intValue());
	}
}
