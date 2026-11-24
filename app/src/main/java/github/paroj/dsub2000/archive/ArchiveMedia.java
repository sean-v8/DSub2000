package github.paroj.dsub2000.archive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

import github.paroj.dsub2000.domain.MusicDirectory.Entry;
import github.paroj.dsub2000.util.FileUtil;

public final class ArchiveMedia {
	private ArchiveMedia() {}

	public static Bitmap artwork(Context context, Entry entry, int size) throws Exception {
		if(size <= 0) size = 512;
		synchronized(ArchiveMedia.class) {
			Bitmap cached = FileUtil.getAlbumArtBitmap(context, entry, size);
			if(cached != null) return cached;
			HttpURLConnection connection = ArchiveHttp.open(ArchiveSource.artworkUrl(entry.getId()), 0);
			byte[] bytes;
			try(InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[8192];
				int count;
				while((count = input.read(buffer)) != -1) {
					if(output.size() + count > 8 * 1024 * 1024) return null;
					output.write(buffer, 0, count);
				}
				bytes = output.toByteArray();
			} finally { connection.disconnect(); }
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inJustDecodeBounds = true;
			BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
			if(options.outWidth <= 0 || options.outHeight <= 0) return null;
			options.inSampleSize = 1;
			while(Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
			options.inJustDecodeBounds = false;
			Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
			if(bitmap == null) return null;
			File file = FileUtil.getAlbumArtFile(context, entry);
			FileUtil.createDirectoryForParent(file);
			AtomicFile atomic = new AtomicFile(file);
			FileOutputStream output = null;
			try {
				output = atomic.startWrite();
				if(!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) throw new java.io.IOException("Could not cache Archive artwork");
				atomic.finishWrite(output);
			} catch(Exception e) {
				if(output != null) atomic.failWrite(output);
				throw e;
			} finally { bitmap.recycle(); }
			return FileUtil.getAlbumArtBitmap(context, entry, size);
		}
	}
}
