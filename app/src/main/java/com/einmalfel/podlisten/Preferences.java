package com.einmalfel.podlisten;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** This class is intended to encapsulate preferences names and default values */
public class Preferences implements SharedPreferences.OnSharedPreferenceChangeListener {
  enum Key {
    STORAGE_PATH,
    MAX_DOWNLOADS,
    REFRESH_INTERVAL,
  }

  enum MaxDownloadsOption {
    ONE, TWO, THREE, FOUR, FIVE, TEN, UNLIMITED;

    public int toInt() {
      switch (this) {
        case TEN:
          return 10;
        case UNLIMITED:
          return Integer.MAX_VALUE;
        default:
          return ordinal() + 1;
      }
    }

    @Override
    public String toString() {
      if (this == UNLIMITED) {
        return PodListenApp.getContext().getString(R.string.preferences_max_downloads_unlimited);
      } else {
        return Integer.toString(toInt());
      }
    }
  }

  enum RefreshIntervalOption {
    NEVER(R.string.refresh_period_never, 0),
    HOUR(R.string.refresh_period_hour, 1),
    HOUR2(R.string.refresh_period_2hours, 2),
    HOUR3(R.string.refresh_period_3hours, 3),
    HOUR6(R.string.refresh_period_6hours, 6),
    HOUR12(R.string.refresh_period_12hours, 12),
    DAY(R.string.refresh_period_day, 24),
    DAY2(R.string.refresh_period_2days, 24 * 2),
    WEEK(R.string.refresh_period_week, 24 * 7),
    WEEK2(R.string.refresh_period_2weeks, 24 * 14),
    MONTH(R.string.refresh_period_month, 30 * 24);

    public final int periodSeconds;
    private final int stringResource;

    RefreshIntervalOption(@StringRes int stringResource, int periodHours) {
      this.periodSeconds = periodHours * 60 * 60;
      this.stringResource = stringResource;
    }

    @Override
    public String toString() {
      return PodListenApp.getContext().getString(stringResource);
    }
  }

  private static final String TAG = "PRF";
  private static final MaxDownloadsOption DEFAULT_MAX_DOWNLOADS = MaxDownloadsOption.TWO;
  private static final RefreshIntervalOption DEFAULT_REFRESH_INTERVAL = RefreshIntervalOption.DAY;
  private static Preferences instance = null;

  // fields below could be changed from readPreference() only
  private MaxDownloadsOption maxDownloads;
  private Storage storage;
  private RefreshIntervalOption refreshInterval;

  private final Context context = PodListenApp.getContext();

  public static Preferences getInstance() {
    if (instance == null) {
      synchronized (Preferences.class) {
        if (instance == null) {
          instance = new Preferences();
        }
      }
    }
    return instance;
  }

  public Preferences() {
    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(PodListenApp.getContext());
    sp.registerOnSharedPreferenceChangeListener(this);
    for (Key key : Key.values()) {
      readPreference(sp, key);
    }
  }

  /**
   * When there is some downloaded episodes on current storage and user asks to switch storage
   * - stop all running downloads
   * - stop and disable sync
   * - stop playback if not streaming (TODO)
   * - reset download progress and download ID fields
   * - remove old files
   * - ask download manager to start downloads for all non-gone episodes
   * - re-enable sync and re-run it to re-download images
   */
  private void clearStorage() {
    Cursor cursor = context.getContentResolver().query(
        Provider.episodeUri, new String[]{Provider.K_EDID}, Provider.K_EDID + " != 0", null, null);

    if (cursor == null) {
      throw new AssertionError("Got null cursor from podlisten provider");
    }
    DownloadManager dM = (DownloadManager) context.getSystemService(
        Context.DOWNLOAD_SERVICE);
    int downloadIdIndex = cursor.getColumnIndexOrThrow(Provider.K_EDID);
    while (cursor.moveToNext()) {
      dM.remove(cursor.getLong(downloadIdIndex));
    }
    cursor.close();

    PodlistenAccount account = PodlistenAccount.getInstance();
    account.setupSync(0);
    account.cancelRefresh();

    ContentValues cv = new ContentValues(4);
    cv.put(Provider.K_EDID, 0);
    cv.put(Provider.K_EDFIN, 0);
    cv.put(Provider.K_EDTSTAMP, 0);
    cv.put(Provider.K_EERROR, (String)null);
    context.getContentResolver().update(Provider.episodeUri, cv, null, null);

    for (File file : storage.getPodcastDir().listFiles()) {
      if (!file.delete()) {
        Log.e(TAG, "Failed to delete " + file.getAbsolutePath());
      }
    }
    for (File file : storage.getImagesDir().listFiles()) {
      if (!file.delete()) {
        Log.e(TAG, "Failed to delete " + file.getAbsolutePath());
      }
    }

    context.sendBroadcast(new Intent(DownloadReceiver.UPDATE_QUEUE_ACTION));
    account.refresh(0);
    account.setupSync(getRefreshInterval().periodSeconds);
  }

  private synchronized void readPreference(SharedPreferences sPrefs, Key key) {
    switch (key) {
      case MAX_DOWNLOADS:
        try {
          int maxDownloadsOrdinal = Integer.valueOf(sPrefs.getString(
              Key.MAX_DOWNLOADS.toString(), "-1"));
          if (maxDownloadsOrdinal == -1) {
            Log.i(TAG, "Setting default max parallel downloads: " + DEFAULT_MAX_DOWNLOADS);
            sPrefs.edit().putString(Key.MAX_DOWNLOADS.toString(),
                                    Integer.toString(DEFAULT_MAX_DOWNLOADS.ordinal())).commit();
          } else {
            maxDownloads = MaxDownloadsOption.values()[maxDownloadsOrdinal];
            context.sendBroadcast(new Intent(DownloadReceiver.UPDATE_QUEUE_ACTION));
          }
        } catch (NumberFormatException exception) {
          Log.e(TAG, "Failed to parse max downloads preference, value remains " + maxDownloads);
        }
        break;
      case REFRESH_INTERVAL:
        try {
          int refreshInt = Integer.valueOf(sPrefs.getString(Key.REFRESH_INTERVAL.toString(), "-1"));
          if (refreshInt == -1) {
            Log.i(TAG, "Setting default refresh interval: " + DEFAULT_REFRESH_INTERVAL);
            sPrefs.edit().putString(Key.REFRESH_INTERVAL.toString(),
                                    Integer.toString(DEFAULT_REFRESH_INTERVAL.ordinal())).commit();
          } else {
            refreshInterval = RefreshIntervalOption.values()[refreshInt];
            PodlistenAccount.getInstance().setupSync(refreshInterval.periodSeconds);
          }
        } catch (NumberFormatException exception) {
          Log.e(TAG, "Failed to parse refresh interval, value remains " + refreshInterval);
        }
      case STORAGE_PATH:
        String storagePreferenceString = sPrefs.getString(Key.STORAGE_PATH.toString(), "");
        if (storagePreferenceString.isEmpty()) {
          // by default, if there are removable storages use first removable, otherwise use last one
          for (Storage storageOption : Storage.getAvailableStorages()) {
            storage = storageOption;
            if (storage.isRemovable()) {
              break;
            }
          }
          if (storage != null) {
            sPrefs.edit().putString(Key.STORAGE_PATH.toString(), storage.toString()).commit();
          }
        } else {
          try {
            Storage newStorage = new Storage(new File(storagePreferenceString));
            newStorage.createSubdirs();
            if (storage != null && !storage.equals(newStorage)) {
              clearStorage();
            }
            storage = newStorage;
          } catch (IOException e) {
            Log.wtf(
                TAG, "Failed to set storage " + storagePreferenceString + ". Reverting to prev", e);
            sPrefs.edit().putString(
                Key.STORAGE_PATH.toString(), storage == null ? "" : storage.toString()).commit();
          }
        }
        break;
    }
  }

  @NonNull
  public RefreshIntervalOption getRefreshInterval() {
    return refreshInterval;
  }

  @NonNull
  public MaxDownloadsOption getMaxDownloads() {
    return maxDownloads;
  }

  @Nullable
  public Storage getStorage() {
    return storage;
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    Log.i(TAG, "Preference changed " + key + ", values: " + sharedPreferences.getAll().toString());
    readPreference(sharedPreferences, Key.valueOf(key));
  }
}
