package com.creativemd.opf.client;

import com.creativemd.opf.OPFrame;
import com.creativemd.opf.client.cache.TextureCache;
import com.porpit.lib.GifDecoder;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

@SideOnly(Side.CLIENT)
public class DownloadThread extends Thread {
	public static final Logger LOGGER = LogManager.getLogger(OPFrame.class);

	public static final TextureCache TEXTURE_CACHE = new TextureCache();
	public static final DateFormat FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
	public static final Object LOCK = new Object();
	public static final int MAXIMUM_ACTIVE_DOWNLOADS = 5;

	public static int activeDownloads = 0;

	public static HashMap<String, PictureTexture> loadedImages = new HashMap<String, PictureTexture>();
	public static Set<String> loadingImages = new HashSet<String>();

	private String url;

	private ProcessedImageData processedImage;
	private boolean failed;
	private boolean complete;

	public DownloadThread(String url) {
		this.url = url;
		synchronized (LOCK) {
			activeDownloads++;
		}
		setName("OPF Download \"" + url + "\"");
		setDaemon(true);
		start();
	}

	public boolean hasFinished() {
		return complete;
	}

	public boolean hasFailed() {
		return hasFinished() && failed;
	}

	@Override
	public void run() {
		try {
			byte[] data = load(url);
			String type = readType(data);
			ByteArrayInputStream in = null;
			try {
				in = new ByteArrayInputStream(data);
				if (type.equalsIgnoreCase("gif")) {
					GifDecoder gif = new GifDecoder();
					int status = gif.read(in);
					if (status == GifDecoder.STATUS_OK) {
						processedImage = new ProcessedImageData(gif);
					}
					else {
						LOGGER.error("Failed to read gif: {}", status);
					}
				}
				else {
					try {
						BufferedImage image = ImageIO.read(in);
						if (image != null) {
							processedImage = new ProcessedImageData(image);
						}
					}
					catch (IOException e1) {
						LOGGER.error("Failed to parse BufferedImage from stream", e1);
					}
				}
			}
			finally {
				IOUtils.closeQuietly(in);
			}
		}
		catch (IOException e) {
			LOGGER.error("An exception occurred while loading OPFrame image", e);
		}
		if (processedImage == null) {
			failed = true;
			TEXTURE_CACHE.deleteEntry(url);
		}
		complete = true;
		synchronized (LOCK) {
			activeDownloads--;
		}
	}

	public static byte[] load(String url) throws IOException {
		TextureCache.CacheEntry entry = TEXTURE_CACHE.getEntry(url);
		long requestTime = System.currentTimeMillis();
		URLConnection connection = new URL(url).openConnection();
		connection.addRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
		int responseCode = -1;
		if (connection instanceof HttpURLConnection) {
			HttpURLConnection httpConnection = (HttpURLConnection) connection;
			if (entry != null) {
				if (entry.getEtag() != null) {
					httpConnection.setRequestProperty("If-None-Match", entry.getEtag());
				} else if (entry.getTime() != -1) {
					httpConnection.setRequestProperty("If-Modified-Since", FORMAT.format(new Date(entry.getTime())));
				}
			}
			responseCode = httpConnection.getResponseCode();
		}
		InputStream in = null;
		try {
			in = connection.getInputStream();
			String etag = connection.getHeaderField("ETag");
			long lastModifiedTimestamp;
			long expireTimestamp = -1;
			String maxAge = connection.getHeaderField("max-age");
			if (maxAge != null && !maxAge.isEmpty()) {
				try {
					expireTimestamp = requestTime + Long.parseLong(maxAge) * 1000;
				}
				catch (NumberFormatException e) {
				}
			}
			String expires = connection.getHeaderField("Expires");
			if (expires != null && !expires.isEmpty()) {
				try {
					expireTimestamp = FORMAT.parse(expires).getTime();
				}
				catch (ParseException e) {
				}
			}
			String lastModified = connection.getHeaderField("Last-Modified");
			if (lastModified != null && !lastModified.isEmpty()) {
				try {
					lastModifiedTimestamp = FORMAT.parse(lastModified).getTime();
				}
				catch (ParseException e) {
					lastModifiedTimestamp = requestTime;
				}
			}
			else {
				lastModifiedTimestamp = requestTime;
			}
			if (entry != null) {
				if (etag != null && !etag.isEmpty()) {
					entry.setEtag(etag);
				}
				entry.setTime(lastModifiedTimestamp);
				if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
					File file = entry.getFile();
					if (file.exists()) {
						return IOUtils.toByteArray(new FileInputStream(file));
					}
				}
			}
			byte[] data = IOUtils.toByteArray(in);
			TEXTURE_CACHE.save(url, etag, lastModifiedTimestamp, expireTimestamp, data);
			return data;
		}
		finally {
			IOUtils.closeQuietly(in);
		}
	}

	private static String readType(byte[] input) throws IOException {
		InputStream in = null;
		try {
			in = new ByteArrayInputStream(input);
			return readType(in);
		}
		finally {
			IOUtils.closeQuietly(in);
		}
	}

	private static String readType(InputStream input) throws IOException {
		ImageInputStream stream = ImageIO.createImageInputStream(input);
		Iterator iter = ImageIO.getImageReaders(stream);
		if (!iter.hasNext()) {
			return "";
		}
		ImageReader reader = (ImageReader) iter.next();
		ImageReadParam param = reader.getDefaultReadParam();
		reader.setInput(stream, true, true);
		try {
			reader.read(0, param);
		}
		catch (IOException e) {
			LOGGER.error("Failed to parse input format", e);
		}
		finally {
			reader.dispose();
			IOUtils.closeQuietly(stream);
		}
		input.reset();
		return reader.getFormatName();
	}

	public static PictureTexture loadImage(DownloadThread thread) {
		PictureTexture texture = null;
		if (!thread.hasFailed()) {
			if (thread.processedImage.isAnimated()) {
				texture = new AnimatedPictureTexture(thread.processedImage);
			}
			else {
				texture = new OrdinaryTexture(thread.processedImage);
			}
		}
		if (texture != null) {
			synchronized (LOCK) {
				loadedImages.put(thread.url, texture);
			}
		}
		return texture;
	}
}
