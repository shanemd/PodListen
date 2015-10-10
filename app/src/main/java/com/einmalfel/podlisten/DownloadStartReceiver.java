package com.einmalfel.podlisten;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class DownloadStartReceiver extends BroadcastReceiver {
  private static final String TAG = "DSR";
  static final String NEW_EPISODE_ACTION = "com.einmalfel.podlisten.NEW_EPISODE";
  static final String DOWNLOAD_HEARTBEAT_ACTION = "com.einmalfel.podlisten.DOWNLOAD_HEARTBEAT";
  static final String UPDATE_QUEUE_ACTION = "com.einmalfel.podlisten.UPDATE_QUEUE";
  static final String URL_EXTRA_NAME = "URL";
  static final String TITLE_EXTRA_NAME = "TITLE";
  static final String ID_EXTRA_NAME = "ID";

  /** @return true if download request was dispatched to DownloadManager, false otherwise */
  private boolean download(Context context, String url, String title, long id) {
    // don't launch d/l while preferences are changing
    synchronized (Preferences.getInstance()) {
      Storage storage = Preferences.getInstance().getStorage();
      if (storage == null || !storage.isAvailableRW()) {
        Log.e(TAG, "Discarding download, as there is no storage, or it isn't writable");
        return false;
      }

      // if file was downloaded before (e.g. partially or with error), remove it
      File target = new File(storage.getPodcastDir(), Long.toString(id));
      if (target.exists() && !target.delete()) {
        Log.e(TAG, "Failed to delete previous download " + target);
        return false;
      }

      DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url))
          .setTitle(title)
          .setAllowedOverMetered(false)
          .setAllowedOverRoaming(false)
          .setDestinationUri(Uri.fromFile(target))
          .setDescription("Downloading podcast " + url)
          .setVisibleInDownloadsUi(false)
          .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);

      DownloadManager dM = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
      long downloadId = dM.enqueue(rq);

      ContentValues cv = new ContentValues(1);
      cv.put(Provider.K_EDID, downloadId);
      context.getContentResolver().update(Provider.getUri(Provider.T_EPISODE, id), cv, null, null);
      return true;
    }
  }


  /**
   * Sometimes body of redirect response is downloaded instead of media file (seen this on xperia
   * Z2 with moscow metro wifi). Such body could be empty or could contain some html code.
   * If downloaded file size is less then 1kB, consider it is an error. If file size is between
   * 1kB and 5MB, check if it is an html page. Podcast episodes are often bigger then 5MB.
   */
  private boolean isDownloadedFileOk(@NonNull String filename) {
    File downloadedFile = new File(filename);
    if (downloadedFile.length() < 1024) {
      return false; // it's to small to be audio file
    } else if (downloadedFile.length() < 5 * 1024 * 1024) {
      try {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        db.parse(downloadedFile);
        return false; // file parsed, assuming it's redirect response body
      } catch (ParserConfigurationException e) {
        Log.wtf(TAG, "Failed to create document builder with default params. Assume file is ok", e);
        return true;
      } catch (SAXException e) {
        return true; // failed to build DOM, it's not html
      } catch (IOException e) {
        Log.e(TAG, "Failed to read file for redirect testing. Assume file is bad", e);
        return false;
      }
    } else {
      return true; // file is big enough, it's probably media, not html
    }
  }

  private void processDownloadResult(Context context, long downloadId) {
    // get episode id and attempts count
    Cursor c = context.getContentResolver().query(
        Provider.episodeUri,
        new String[]{Provider.K_ID, Provider.K_EDATT},
        Provider.K_EDID + " == ?",
        new String[]{Long.toString(downloadId)},
        null);
    int count = c.getCount();
    if (count != 1) {
      Log.e(TAG, "Wrong number(" + count + ") of episodes for completed download #" + downloadId);
      c.close();
      return;
    }
    c.moveToFirst();
    long episodeId = c.getLong(c.getColumnIndexOrThrow(Provider.K_ID));
    long attempts = c.getLong(c.getColumnIndexOrThrow(Provider.K_EDATT));
    c.close();

    // get download status and filename
    DownloadManager dM = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    c = dM.query(new DownloadManager.Query().setFilterById(downloadId));
    if (!c.moveToFirst()) {
      Log.e(TAG, "DownloadManager query failed");
      c.close();
      return;
    }
    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
    String fileName = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
    int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
    c.close();

    // update episode download state
    ContentValues values = new ContentValues(4);
    values.put(Provider.K_EDID, 0);
    if (status == DownloadManager.STATUS_SUCCESSFUL && isDownloadedFileOk(fileName)) {
      Log.i(TAG, "Successfully downloaded " + episodeId);
      values.put(Provider.K_EDFIN, 100);
      values.put(Provider.K_ESIZE, new File(fileName).length());
      // try get length
      MediaMetadataRetriever mmr = new MediaMetadataRetriever();
      String durationString = null;
      // setDataSource may throw RuntimeException for damaged media file
      try {
        mmr.setDataSource(fileName);
        durationString = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      } catch (RuntimeException exception) {
        Log.e(TAG, "Failed to get duration of " + fileName, exception);
      }
      if (durationString != null) {
        try {
          Long duration = Long.parseLong(durationString);
          values.put(Provider.K_ELENGTH, duration);
        } catch (NumberFormatException ignored) {
          Log.e(TAG, fileName + ": Wrong duration metadata: " + durationString);
        }
      }
      mmr.release();
    } else {
      Log.w(TAG, episodeId + " download failed, reason " + reason);
      values.put(Provider.K_EDFIN, 0);
      values.put(Provider.K_EDATT, attempts + 1);
    }
    int updated = context.getContentResolver().update(
        Provider.getUri(Provider.T_EPISODE, episodeId), values, null, null);
    if (updated != 1) {
      Log.e(TAG, "Something went wrong while updating " + episodeId + ", updated " + updated);
    }
  }

  private int getRunningCount(Context context) {
    DownloadManager dM = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    DownloadManager.Query query = new DownloadManager.Query().setFilterByStatus(
        DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING);
    Cursor cursor = dM.query(query);
    int runningCount = cursor.getCount();
    cursor.close();
    return runningCount;
  }

  private void updateDownloadQueue(Context context) {
    int runningDownloadsCount = getRunningCount(context);
    int maxParallelDownloads = Preferences.getInstance().getMaxDownloads().toInt();
    if (runningDownloadsCount >= maxParallelDownloads) {
      return;
    }

    Cursor queue = context.getContentResolver().query(
        Provider.episodeUri,
        new String[]{Provider.K_EAURL, Provider.K_ENAME, Provider.K_ID},
        Provider.K_EDID + " == ? AND " + Provider.K_EDATT + " < ? AND " + Provider.K_ESTATE + " != ? AND " + Provider.K_EDFIN + " != ?",
        new String[]{"0", "50000000000", Integer.toString(Provider.ESTATE_GONE), "100"},
        Provider.K_EDATT + " ASC, " + Provider.K_EDATE + " ASC");
    if (queue == null) {
      throw new AssertionError("Unexpectedly got null while querying provider");
    }
    int urlInd = queue.getColumnIndexOrThrow(Provider.K_EAURL);
    int titleInd = queue.getColumnIndexOrThrow(Provider.K_ENAME);
    int idInd = queue.getColumnIndexOrThrow(Provider.K_ID);
    while (queue.moveToNext() && runningDownloadsCount < maxParallelDownloads) {
      if (download(
          context, queue.getString(urlInd), queue.getString(titleInd), queue.getLong(idInd))) {
        runningDownloadsCount++;
      }
    }
    queue.close();
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    switch (intent.getAction()) {
      case DOWNLOAD_HEARTBEAT_ACTION:
        updateProgress(context);
        break;
      case UPDATE_QUEUE_ACTION:
        updateDownloadQueue(context);
        break;
      case NEW_EPISODE_ACTION:
        if (getRunningCount(context) < Preferences.getInstance().getMaxDownloads().toInt()) {
          download(context,
                   intent.getStringExtra(URL_EXTRA_NAME),
                   intent.getStringExtra(TITLE_EXTRA_NAME),
                   intent.getLongExtra(ID_EXTRA_NAME, -1));
        }
        break;
      case DownloadManager.ACTION_NOTIFICATION_CLICKED:
        Intent i = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
        break;
      case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
        processDownloadResult(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L));
        updateDownloadQueue(context);
        break;
    }
  }

  private void updateProgress(Context context) {
    //TODO call this asynchronously
    Cursor c = context.getContentResolver().query(
        Provider.episodeUri,
        new String[]{Provider.K_ID, Provider.K_EDID},
        Provider.K_EDID + " != ?",
        new String[]{"0"},
        null);
    while (c.moveToNext()) {
      long downLoadId = c.getLong(c.getColumnIndexOrThrow(Provider.K_EDID));
      long id = c.getLong(c.getColumnIndexOrThrow(Provider.K_ID));
      DownloadManager dM = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
      Cursor q = dM.query(new DownloadManager.Query().setFilterById(downLoadId));
      if (q.moveToFirst()) {
        int state = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        // WORKAROUND: sometimes ACTION_DOWNLOAD_COMPLETE is somehow not received (or there was an
        // exception in callback), so handle there episodes completed more than a minute ago
        if (state == DownloadManager.STATUS_SUCCESSFUL || state == DownloadManager.STATUS_FAILED) {
          long t = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
          if (System.currentTimeMillis() - t > 60000) {
            Log.e(TAG, "Found lost completed download, processing " + downLoadId);
            processDownloadResult(context, downLoadId);
          }
        } else {
          int got = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
          int total = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
          // ignore dubious data. E.g. sometimes it reports total size is -1B or 128B
          if (got > 0 && total > 1000 && total > got) {
            ContentValues v = new ContentValues(2);
            v.put(Provider.K_EDFIN, 100L * got / total);
            v.put(Provider.K_ESIZE, total);
            context.getContentResolver().update(
                Provider.getUri(Provider.T_EPISODE, id), v, null, null);
          }
        }
      } else {
        Log.e(TAG, "Failed to obtain download info for episode " + id + ". Resetting K_EDID to 0");
        ContentValues v = new ContentValues(2);
        v.put(Provider.K_EDID, 0);
        context.getContentResolver().update(Provider.getUri(Provider.T_EPISODE, id), v, null, null);
      }
      q.close();
    }
    c.close();
  }
}
