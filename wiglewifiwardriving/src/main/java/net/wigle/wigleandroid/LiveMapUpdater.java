package net.wigle.wigleandroid;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import android.util.Log; // added for debug logging

/**
 * Simple helper to collect best-per-device detections and push GeoJSON updates to VectorMapActivity.
 */
public class LiveMapUpdater {
    private static final Map<String, Dev> wifiMap = new HashMap<>();
    private static final Map<String, double[]> btMap = new HashMap<>();
    private static final Map<String, double[]> cellMap = new HashMap<>();

    private static WeakReference<VectorMapActivity> activityRef = new WeakReference<>(null);

    // Unique token to search for in adb logcat for all live layer related events
    private static final String LIVE_DEBUG_TOKEN = "WIGLE_LIVE_DBG";

    public static synchronized void setActiveActivity(VectorMapActivity activity) {
        activityRef = new WeakReference<>(activity);
        // push current state
        pushAllToActivity();
    }

    public static synchronized void clearActiveActivity() {
        activityRef = new WeakReference<>(null);
    }

    /**
     * Clear all tracked devices. Call this when the database is cleared.
     */
    public static synchronized void clearAllDevices() {
        wifiMap.clear();
        btMap.clear();
        cellMap.clear();
        Log.i("LiveMapUpdater", LIVE_DEBUG_TOKEN + ": clearAllDevices called");
        // Push empty state to any active map activity
        pushAllToActivity();
    }

    private static class Dev {
        double lat;
        double lon;
        int signal;
        String security;
        Dev(double lat, double lon, int signal, String security) {
            this.lat = lat; this.lon = lon; this.signal = signal; this.security = security;
        }
    }

    public static synchronized void addWifiDevice(String id, double lat, double lon, int signal, String security) {
        addBest(wifiMap, id, lat, lon, signal, security);
        pushWifi();
    }

    public static synchronized void addBtDevice(String id, double lat, double lon, int signal) {
        addBest(btMap, id, lat, lon, signal);
        pushBt();
    }

    public static synchronized void addCellDevice(String id, double lat, double lon, int signal) {
        addBest(cellMap, id, lat, lon, signal);
        pushCell();
    }

    private static void addBest(Map map, String id, double lat, double lon, int signal) {
        // generic for bt/cell maps
        double[] cur = (double[]) map.get(id);
        if (cur == null || signal > (int)cur[2]) {
            map.put(id, new double[]{lat, lon, signal});
        }
    }

    private static void addBest(Map<String,Dev> map, String id, double lat, double lon, int signal, String security) {
        Dev cur = map.get(id);
        if (cur == null || signal > cur.signal) {
            map.put(id, new Dev(lat, lon, signal, security));
        }
    }

    private static void pushAllToActivity() {
        pushWifi();
        pushBt();
        pushCell();
    }

    private static void pushWifi() {
        VectorMapActivity act = activityRef.get();
        if (act == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (Map.Entry<String, Dev> e : wifiMap.entrySet()) {
            final String id = e.getKey();
            final Dev d = e.getValue();
            if (d == null) continue;
            if (!first) sb.append(',');
            first = false;
            // normalize security to expected values and map to numeric sectype
            String sec = d.security == null ? "secure" : d.security.toLowerCase(Locale.US);
            int sectype = 3;
            if (sec.contains("open")) { sec = "open"; sectype = 1; }
            else if (sec.contains("wep")) { sec = "wep"; sectype = 2; }
            else { sec = "secure"; sectype = 3; }

            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(d.lon).append(',').append(d.lat).append(" ]},\"properties\":{\"id\":\"").append(id).append("\",\"type\":\"wifi\",\"signal\":\"").append(d.signal).append("\",\"security\":\"").append(sec).append("\",\"sectype\":").append(sectype).append("}}");
        }
        sb.append("]}");
        final String json = sb.toString();
        // Log a concise debug line with a searchable token
        Log.i("LiveMapUpdater", LIVE_DEBUG_TOKEN + ": pushWifi features=" + wifiMap.size());
        act.runOnUiThread(() -> act.setLiveWifiGeoJson(json));
    }

    private static void pushBt() {
        VectorMapActivity act = activityRef.get();
        if (act == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (Map.Entry<String, double[]> e : btMap.entrySet()) {
            final String id = e.getKey();
            final double[] d = e.getValue();
            if (d == null || d.length < 2) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(d[1]).append(',').append(d[0]).append(" ]},\"properties\":{\"id\":\"").append(id).append("\",\"type\":\"bt\",\"signal\":\"").append((int)d[2]).append("\"}}");
        }
        sb.append("]}");
        final String json = sb.toString();
        Log.i("LiveMapUpdater", LIVE_DEBUG_TOKEN + ": pushBt features=" + btMap.size());
        act.runOnUiThread(() -> act.setLiveBtGeoJson(json));
    }

    private static void pushCell() {
        VectorMapActivity act = activityRef.get();
        if (act == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (Map.Entry<String, double[]> e : cellMap.entrySet()) {
            final String id = e.getKey();
            final double[] d = e.getValue();
            if (d == null || d.length < 2) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(d[1]).append(',').append(d[0]).append(" ]},\"properties\":{\"id\":\"").append(id).append("\",\"type\":\"cell\",\"signal\":\"").append((int)d[2]).append("\"}}");
        }
        sb.append("]}");
        final String json = sb.toString();
        Log.i("LiveMapUpdater", LIVE_DEBUG_TOKEN + ": pushCell features=" + cellMap.size());
        act.runOnUiThread(() -> act.setLiveCellGeoJson(json));
    }
}
