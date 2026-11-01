package github.paroj.dsub2000.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import github.paroj.dsub2000.R;
import github.paroj.dsub2000.archive.ArchiveCatalog;
import github.paroj.dsub2000.archive.ArchiveClient;
import github.paroj.dsub2000.archive.ArchiveLibrary;
import github.paroj.dsub2000.archive.ArchiveSource;
import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.service.DownloadService;
import github.paroj.dsub2000.util.ProgressListener;

/** Native Archive browser; independent of server selection and authentication. */
public class ArchiveFragment extends SubsonicFragment {
	private static final int COLLECTIONS = 0, YEARS = 1, DATES = 2, RECORDINGS = 3, TRACKS = 4, SAVED_ARTISTS = 5, SAVED_YEARS = 6, SAVED_RECORDINGS = 7;
	private static final int FIND = 8101, OPEN = 8102, FAVORITES = 8103, PIN = 8104,
			FORMAT = 8105, WEB = 8106, DOWNLOAD = 8107, REFRESH = 8108, DOWNLOADED = 8109;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private ExecutorService executor;
	private Future<?> task;
	private int generation;
	private int level;
	private int page = 1;
	private int total;
	private String collection = "";
	private String collectionTitle = "";
	private String year = "";
	private String date = "";
	private String identifier = "";
	private String query = "";
	private boolean favorites;
	private String savedArtist = "";
	private boolean savedOnly;
	private boolean loading;
	private List<ArchiveCatalog.Row> rows = new ArrayList<>();
	private List<Entry> tracks = new ArrayList<>();
	private ArchiveCatalog.Recording recording;
	private ListView list;
	private TextView status;

	public static ArchiveFragment recording(String id) {
		ArchiveFragment fragment = new ArchiveFragment();
		Bundle args = new Bundle();
		args.putInt("level", TRACKS);
		args.putString("identifier", ArchiveSource.identifier(id));
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		Bundle args = state != null ? state : getArguments();
		if(args != null) {
			level = args.getInt("level", COLLECTIONS);
			page = args.getInt("page", 1);
			collection = args.getString("collection", "");
			collectionTitle = args.getString("collectionTitle", collection);
			year = args.getString("year", "");
			date = args.getString("date", "");
			identifier = args.getString("identifier", "");
			query = args.getString("query", "");
			favorites = args.getBoolean("favorites", false);
			savedArtist = args.getString("savedArtist", "");
			savedOnly = args.getBoolean("savedOnly", false);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle state) {
		super.onSaveInstanceState(state);
		state.putAll(arguments());
	}

	private Bundle arguments() {
		Bundle args = new Bundle();
		args.putInt("level", level);
		args.putInt("page", page);
		args.putString("collection", collection);
		args.putString("collectionTitle", collectionTitle);
		args.putString("year", year);
		args.putString("date", date);
		args.putString("identifier", identifier);
		args.putString("query", query);
		args.putBoolean("favorites", favorites);
		args.putString("savedArtist", savedArtist);
		args.putBoolean("savedOnly", savedOnly);
		return args;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		rootView = inflater.inflate(R.layout.archive_browser, container, false);
		status = rootView.findViewById(R.id.archive_status);
		list = rootView.findViewById(R.id.archive_list);
		refreshLayout = rootView.findViewById(R.id.refresh_layout);
		refreshLayout.setOnRefreshListener(this);
		executor = Executors.newSingleThreadExecutor();
		list.setOnItemClickListener((parent, view, position, id) -> clicked(position));
		list.setOnItemLongClickListener((parent, view, position, id) -> {
			if(level != TRACKS || loading || position >= tracks.size()) return false;
			trackActions(position + (page - 1) * ArchiveClient.PAGE_SIZE);
			return true;
		});
		rootView.findViewById(R.id.archive_previous).setOnClickListener(v -> { if(!loading && page > 1) { page--; refresh(false); } });
		rootView.findViewById(R.id.archive_next).setOnClickListener(v -> { if(!loading && page * ArchiveClient.PAGE_SIZE < total) { page++; refresh(false); } });
		rootView.findViewById(R.id.archive_play).setOnClickListener(v -> play(0));
		rootView.findViewById(R.id.archive_enqueue).setOnClickListener(v -> enqueue(tracks, false));
		rootView.findViewById(R.id.archive_details).setOnClickListener(v -> details());
		refresh(false);
		return rootView;
	}

	@Override
	public void onDestroyView() {
		generation++;
		if(task != null) task.cancel(true);
		if(executor != null) executor.shutdownNow();
		list = null;
		status = null;
		super.onDestroyView();
	}

	private SharedPreferences preferences() {
		return context.getSharedPreferences("archive", android.content.Context.MODE_PRIVATE);
	}

	private ArchiveCatalog.Format format() {
		return preferences().getBoolean("flac", false) ? ArchiveCatalog.Format.FLAC : ArchiveCatalog.Format.MP3;
	}

	private Set<String> pinned() {
		return new HashSet<>(preferences().getStringSet("collections", Collections.emptySet()));
	}

	@Override
	protected void refresh(boolean refresh) {
		if(list == null) return;
		int request = ++generation;
		if(task != null) task.cancel(true);
		loading = true;
		refreshLayout.setRefreshing(true);
		status.setText(R.string.archive_loading);
		rootView.findViewById(R.id.archive_actions).setVisibility(View.GONE);
		rootView.findViewById(R.id.archive_pages).setVisibility(View.GONE);
		setTitle(level == COLLECTIONS ? getString(R.string.archive_title)
				: level == TRACKS ? identifier : collectionTitle + (level == DATES ? " · " + year : level == RECORDINGS ? " · " + date : ""));
		if(level==SAVED_ARTISTS) setTitle(R.string.archive_downloaded);
		else if(level==SAVED_YEARS || level==SAVED_RECORDINGS) setTitle(savedArtist + (level==SAVED_RECORDINGS ? " · " + year : ""));
		final int selectedPage=page;
		final ArchiveCatalog.Format selectedFormat = format();
		// Capture preferences on the main thread, not a detached Activity in a worker.
		final Set<String> favoriteIds = pinned();
		final java.util.Map<String, String> favoriteNames = new java.util.HashMap<>();
		for(String id : favoriteIds) favoriteNames.put(id, preferences().getString("title:" + id, id));
		final ArchiveClient client = new ArchiveClient(context);
		task = executor.submit(() -> {
			try {
				List<ArchiveCatalog.Row> result;
				ArchiveCatalog.Recording loaded = null;
				int count = 0;
				if(level == COLLECTIONS && favorites) {
					result = new ArrayList<>();
					for(String id : favoriteIds) result.add(new ArchiveCatalog.Row(id, favoriteNames.get(id), id, 0));
					result.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
				} else if(level == COLLECTIONS) {
					ArchiveClient.Page response = client.collections(query, page, refresh);
					result = response.rows;
					count = response.total;
					if(page == 1 && query.isEmpty()) {
						result.removeIf(row -> row.id.equals("taperssection"));
						result.add(0, new ArchiveCatalog.Row("taperssection", "Tapers Section", "taperssection", 0));
					}
				} else if(level == YEARS || level == DATES) {
					ProgressListener progress = new ProgressListener() {
						@Override public void updateProgress(String message) { handler.post(() -> { if(request == generation && status != null) status.setText(message); }); }
						@Override public void updateProgress(int id) {}
						@Override public void updateCache(int code) {}
					};
					result = level == YEARS ? client.years(collection, refresh, progress) : client.dates(collection,year,refresh,progress);
				} else if(level == RECORDINGS) {
					ArchiveClient.Page response = client.recordings(collection, date, refresh, page);
					result = response.rows;
					count = response.total;
				} else if(level==SAVED_ARTISTS || level==SAVED_YEARS || level==SAVED_RECORDINGS) {
					ArchiveLibrary library=ArchiveLibrary.get(context);
					ArchiveClient.Page response=level==SAVED_ARTISTS ? library.artists(selectedPage)
							: level==SAVED_YEARS ? library.years(savedArtist,selectedPage) : library.recordings(savedArtist,year,selectedPage);
					result=response.rows;count=response.total;
				} else {
					loaded = client.recording(identifier, selectedFormat, refresh, savedOnly);
					result = new ArrayList<>();
					for(ArchiveCatalog.Track track : loaded.tracks) {
						result.add(new ArchiveCatalog.Row(track.id, track.track + ". " + track.title,
								track.duration / 60 + ":" + String.format(java.util.Locale.US, "%02d", track.duration % 60)
								+ " · " + track.suffix.toUpperCase(java.util.Locale.US), 0));
					}
				}
				if(level==YEARS || level==DATES || level==TRACKS || (level==COLLECTIONS && favorites)) {
					count=result.size();
					long start=(long)(selectedPage-1)*ArchiveClient.PAGE_SIZE;
					result=start>=result.size() ? new ArrayList<>() : new ArrayList<>(result.subList((int)start,(int)Math.min(result.size(),start+ArchiveClient.PAGE_SIZE)));
				}
				final List<Entry> finalEntries=loaded==null ? new ArrayList<>() : client.entries(loaded);
				final List<ArchiveCatalog.Row> finalResult = result;
				final ArchiveCatalog.Recording finalRecording = loaded;
				final int finalCount = count;
				handler.post(() -> {
					if(request != generation || list == null) return;
					loading = false;
					refreshLayout.setRefreshing(false);
					rows = finalResult;
					total = finalCount;
					recording = finalRecording;
					tracks = finalEntries;
					showRows(client.isCached());
					context.invalidateOptionsMenu();
				});
			} catch(Exception e) {
				handler.post(() -> {
					if(request != generation || status == null) return;
					loading = false;
					refreshLayout.setRefreshing(false);
					status.setText(getString(R.string.archive_error, e.getMessage()));
				});
			}
		});
	}

	private void showRows(boolean cached) {
		list.setAdapter(new ArrayAdapter<ArchiveCatalog.Row>(context, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
			@Override public View getView(int position, View convert, ViewGroup parent) {
				View view = super.getView(position, convert, parent);
				ArchiveCatalog.Row row = getItem(position);
				((TextView) view.findViewById(android.R.id.text1)).setText(row.title);
				TextView detail = view.findViewById(android.R.id.text2);
				detail.setText(row.count > 0 ? getResources().getQuantityString(R.plurals.archive_recordings, row.count, row.count) : row.detail);
				detail.setMaxLines(4);
				return view;
			}
		});
		String text = rows.isEmpty() ? getString(level == TRACKS ? R.string.archive_no_tracks
				: favorites && level == COLLECTIONS ? R.string.archive_no_favorites : R.string.archive_empty) : "";
		if(total > 0) text = getResources().getQuantityString(R.plurals.archive_page, total, page, total);
		if(recording != null) {
			setTitle(recording.title);
			java.util.Set<String> formats = new java.util.TreeSet<>();
			for(Entry entry : tracks) formats.add(entry.getSuffix().toUpperCase(java.util.Locale.US));
			text = tracks.isEmpty() ? getString(R.string.archive_no_tracks)
					: getResources().getQuantityString(R.plurals.archive_tracks, tracks.size(), tracks.size(), android.text.TextUtils.join(" / ", formats));
			if(recording.streamOnly) text += "\n" + getString(R.string.archive_stream_only);
			rootView.findViewById(R.id.archive_actions).setVisibility(View.VISIBLE);
			rootView.findViewById(R.id.archive_play).setEnabled(!tracks.isEmpty());
			rootView.findViewById(R.id.archive_enqueue).setEnabled(!tracks.isEmpty());
		}
		if(cached) text = ArchiveCatalog.join(text, getString(R.string.archive_cached));
		status.setText(text);
		boolean paged = total > ArchiveClient.PAGE_SIZE || page > 1;
		rootView.findViewById(R.id.archive_pages).setVisibility(paged ? View.VISIBLE : View.GONE);
		rootView.findViewById(R.id.archive_previous).setEnabled(page > 1);
		rootView.findViewById(R.id.archive_next).setEnabled(page * ArchiveClient.PAGE_SIZE < total);
	}

	private void clicked(int position) {
		if(loading || position >= rows.size()) return;
		ArchiveCatalog.Row row = rows.get(position);
		if(level == TRACKS) { play(position + (page - 1) * ArchiveClient.PAGE_SIZE); return; }
		Bundle args = arguments();
		args.putInt("page", 1);
		args.putInt("level", level + 1);
		if(level == SAVED_ARTISTS) args.putString("savedArtist",row.id);
		else if(level == SAVED_YEARS) args.putString("year",row.id);
		else if(level == SAVED_RECORDINGS) { args.putInt("level",TRACKS);args.putString("identifier",row.id);args.putBoolean("savedOnly",true); }
		else if(level == COLLECTIONS) {
			args.putString("collection", row.id);
			args.putString("collectionTitle", row.title);
		} else if(level == YEARS) {
			args.putString("year", row.id);
			if(row.id.equals("unknown")) { args.putInt("level", RECORDINGS); args.putString("date", "unknown"); }
		} else if(level == DATES) args.putString("date", row.id);
		else if(level == RECORDINGS) args.putString("identifier", row.id);
		ArchiveFragment fragment = new ArchiveFragment();
		fragment.setArguments(args);
		replaceFragment(fragment, false);
	}

	private void play(int start) {
		if(loading || tracks.isEmpty()) return;
		DownloadService service = getDownloadService();
		if(service == null) { toast(R.string.archive_player_unavailable); return; }
		service.clear();
		service.download(new ArrayList<>(tracks), false, true, false, false, start, 0);
		context.openNowPlaying();
	}

	private void enqueue(List<Entry> entries, boolean save) {
		if(loading || entries.isEmpty() || (save && recording != null && recording.streamOnly)) return;
		DownloadService service = getDownloadService();
		if(service == null) { toast(R.string.archive_player_unavailable); return; }
		if(save) service.downloadBackground(new ArrayList<>(entries), true);
		else service.download(new ArrayList<>(entries), false, false, false, false);
		toast(save ? R.string.archive_downloading : R.string.archive_queued);
	}

	private void trackActions(int position) {
		boolean downloadable = recording != null && !recording.streamOnly;
		String[] labels = downloadable ? new String[]{getString(R.string.archive_play_track), getString(R.string.archive_enqueue_track), getString(R.string.archive_download_track)}
				: new String[]{getString(R.string.archive_play_track), getString(R.string.archive_enqueue_track)};
		new MaterialAlertDialogBuilder(context).setTitle(tracks.get(position).getTitle()).setItems(labels, (dialog, which) -> {
			if(which == 0) play(position);
			else enqueue(Collections.singletonList(tracks.get(position)), which == 2);
		}).show();
	}

	private void details() {
		if(recording == null) return;
		new MaterialAlertDialogBuilder(context).setTitle(recording.title)
				.setMessage(Html.fromHtml(recording.details.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY))
				.setPositiveButton(android.R.string.ok, null)
				.setNeutralButton(R.string.archive_open_web, (dialog, which) -> openWeb()).show();
	}

	private void toast(int text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		if(!primaryFragment) return;
		menu.add(0, REFRESH, 0, R.string.menu_refresh);
		menu.add(0, DOWNLOADED, 1, R.string.archive_downloaded);
		if(level == COLLECTIONS) {
			menu.add(0, FIND, 1, R.string.archive_find_collection);
			menu.add(0, OPEN, 2, R.string.archive_open_collection);
			menu.add(0, FAVORITES, 3, favorites ? R.string.archive_all_collections : R.string.archive_favorites);
		} else if(!collection.isEmpty()) {
			menu.add(0, PIN, 1, pinned().contains(collection) ? R.string.archive_unfavorite : R.string.archive_favorite);
		}
		menu.add(0, FORMAT, 4, R.string.archive_format);
		if(level == TRACKS) {
			menu.add(0, WEB, 5, R.string.archive_open_web);
			if(recording != null && !recording.streamOnly && !tracks.isEmpty()) menu.add(0, DOWNLOAD, 6, R.string.archive_download);
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch(item.getItemId()) {
			case REFRESH: refresh(true); return true;
			case DOWNLOADED:
				ArchiveFragment local=new ArchiveFragment();Bundle args=new Bundle();
				args.putInt("level",SAVED_ARTISTS);local.setArguments(args);replaceFragment(local,false);return true;
			case FIND: input(false); return true;
			case OPEN: input(true); return true;
			case FAVORITES: favorites = !favorites; page = 1; refresh(false); context.invalidateOptionsMenu(); return true;
			case PIN:
				Set<String> ids = pinned();
				if(!ids.remove(collection)) ids.add(collection);
				preferences().edit().putStringSet("collections", ids).putString("title:" + collection, collectionTitle).apply();
				context.invalidateOptionsMenu(); return true;
			case FORMAT:
				new MaterialAlertDialogBuilder(context).setTitle(R.string.archive_format)
						.setSingleChoiceItems(new String[]{getString(R.string.archive_format_mp3), getString(R.string.archive_format_flac)}, format() == ArchiveCatalog.Format.MP3 ? 0 : 1,
								(dialog, which) -> { preferences().edit().putBoolean("flac", which == 1).apply(); dialog.dismiss(); if(level == TRACKS) refresh(false); }).show(); return true;
			case WEB: openWeb(); return true;
			case DOWNLOAD: enqueue(tracks, true); return true;
			default: return super.onOptionsItemSelected(item);
		}
	}

	private void input(boolean byId) {
		EditText input = new EditText(context);
		input.setSingleLine(true);
		input.setHint(byId ? R.string.archive_identifier_hint : R.string.archive_find_collection);
		if(!byId) input.setText(query);
		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
				.setTitle(byId ? R.string.archive_open_collection : R.string.archive_find_collection).setView(input)
				.setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
		dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String value = input.getText().toString().trim();
			if(byId) {
				try { ArchiveSource.validateIdentifier(value); }
				catch(IllegalArgumentException e) { input.setError(getString(R.string.archive_invalid_identifier)); return; }
				ArchiveFragment fragment = new ArchiveFragment();
				Bundle args = new Bundle();
				args.putInt("level", YEARS); args.putString("collection", value); args.putString("collectionTitle", value);
				fragment.setArguments(args); replaceFragment(fragment, false);
			} else { query = value; favorites = false; page = 1; refresh(false); }
			dialog.dismiss();
		}));
		dialog.show();
	}

	private void openWeb() {
		startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ArchiveSource.detailsUrl(ArchiveSource.itemId(identifier)))));
	}
}
