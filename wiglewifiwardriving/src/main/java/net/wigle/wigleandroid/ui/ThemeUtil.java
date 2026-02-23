package net.wigle.wigleandroid.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatDelegate;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThemeUtil {
    public static void setTheme(final SharedPreferences prefs) {
        if (Build.VERSION.SDK_INT > 28) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Handler handler = new Handler(Looper.getMainLooper());
            executor.execute(() -> {
                final int displayMode = prefs.getInt(PreferenceKeys.PREF_DAYNIGHT_MODE, AppCompatDelegate.MODE_NIGHT_YES);
                Logging.info("set theme called: " + displayMode);
                handler.post(() -> AppCompatDelegate.setDefaultNightMode(displayMode));
            });
        } else {
            //Force night mode
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    public static void setNavTheme(final Window w, final Context c, final SharedPreferences prefs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            final int displayMode = prefs.getInt(PreferenceKeys.PREF_DAYNIGHT_MODE, AppCompatDelegate.MODE_NIGHT_YES);
            final int nightModeFlags = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            handler.post(() -> {
                if (AppCompatDelegate.MODE_NIGHT_YES == displayMode ||
                        (AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM == displayMode &&
                                nightModeFlags == Configuration.UI_MODE_NIGHT_YES)) {
                    w.setNavigationBarColor(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                }
            });
        });
    }

    public static void setMapTheme(final MapLibreMap mapLibreMap, final Context c, final SharedPreferences prefs, final int mapNightThemeId) {
        if (shouldUseMapNightMode(c, prefs)) {
            try (InputStream is = c.getResources().openRawResource(mapNightThemeId)) {
                byte[] bytes = is.readAllBytes();
                String json = new String(bytes, StandardCharsets.UTF_8);
                Style.Builder builder = new Style.Builder().fromJson(json);
                mapLibreMap.setStyle(builder);
            } catch (Resources.NotFoundException e) {
                Logging.error("Unable to theme map: ", e);
            } catch (Exception e) {
                Logging.error("Unable to apply map style: ", e);
            }
        }
    }

    public static boolean shouldUseMapNightMode(final Context c, final SharedPreferences prefs) {
        final boolean mapsMatchMode = prefs.getBoolean(PreferenceKeys.PREF_MAPS_FOLLOW_DAYNIGHT, false);
        if (mapsMatchMode) {
            final int displayMode = prefs.getInt(PreferenceKeys.PREF_DAYNIGHT_MODE, AppCompatDelegate.MODE_NIGHT_YES);
            final int nightModeFlags = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return AppCompatDelegate.MODE_NIGHT_YES == displayMode ||
                    (AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM == displayMode &&
                            nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
        }
        return false;
    }
}
