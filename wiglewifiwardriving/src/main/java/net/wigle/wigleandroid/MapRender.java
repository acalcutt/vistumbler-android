package net.wigle.wigleandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.expressions.Expression;

import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/**
 * Custom map rendering: clustering, label decisions, MapUtils functionality
 */
public class MapRender {

        private final boolean isDbResult;
        private final AtomicInteger networkCount = new AtomicInteger();
        private final SharedPreferences prefs;
        private final MapLibreMap map;
        private Matcher ssidMatcher;
        private final Set<Network> labeledNetworks = Collections.newSetFromMap(
            new ConcurrentHashMap<>());
        // Separate clustered sources per WiFi crypto so clusters don't mix security types
        private GeoJsonSource liveOpenSource;
        private GeoJsonSource liveWepSource;
        private GeoJsonSource liveWpaSource;
        private GeoJsonSource liveBtSource;
        private GeoJsonSource liveCellSource;
        // prefix for source/layer ids; DB-result renderers should use their own prefixed sources
        private final String sourcePrefix;
        private final List<Feature> liveOpenFeatures = Collections.synchronizedList(new ArrayList<>());
        private final List<Feature> liveWepFeatures = Collections.synchronizedList(new ArrayList<>());
        private final List<Feature> liveBtFeatures = Collections.synchronizedList(new ArrayList<>());
        private final List<Feature> liveCellFeatures = Collections.synchronizedList(new ArrayList<>());
        private final List<Feature> liveWpaFeatures = Collections.synchronizedList(new ArrayList<>());

    private static final String MESSAGE_BSSID = "messageBssid";
    private static final String MESSAGE_BSSID_LIST = "messageBssidList";
    //ALIBI: placeholder while we sort out "bubble" icons for BT, BLE, Cell, Cell NR, WiFi encryption types.
    // Icons removed; using CircleLayer colors instead

    private static final float DEFAULT_ICON_ALPHA = 0.7f;
    private static final float CUSTOM_ICON_ALPHA = 0.75f;
    // a % of the cache size can be labels
    private static final int MAX_LABELS = MainActivity.getNetworkCache().maxSize() / 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MapRender(final Context context, final MapLibreMap map, final boolean isDbResult) {
        this.map = map;
        this.isDbResult = isDbResult;
        prefs = context.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
        ssidMatcher = FilterMatcher.getSsidFilterMatcher( prefs, MappingFragment.MAP_DIALOG_PREFIX );
        // initialize prefix early so final field is always set
        this.sourcePrefix = isDbResult ? "db_" : "live_";

        try {
            try {
                Logging.info("MapRender: instance=" + System.identityHashCode(this) + " created for map instance=" + System.identityHashCode(map) + " isDbResult=" + isDbResult);
            } catch (Throwable t) {}
            try {
                // log caller stack to help identify who created this MapRender
                final StackTraceElement[] st = Thread.currentThread().getStackTrace();
                StringBuilder sb = new StringBuilder();
                // skip first few stack frames that are JVM/internal
                for (int i = 3; i < Math.min(10, st.length); i++) {
                    sb.append(st[i].toString()).append(" |");
                }
                Logging.info("MapRender: ctor stack: " + sb.toString());
            } catch (Throwable t) {}
                    // create GeoJson sources (open, wep, wpa+, bt, cell) so clustering doesn't mix types
                // For DB result views, disable clustering so individual points render immediately.
                final GeoJsonOptions clusteredOptions = new GeoJsonOptions().withCluster(true).withClusterRadius(50);
                final GeoJsonOptions noClusterOptions = new GeoJsonOptions();
                final GeoJsonOptions opts = isDbResult ? noClusterOptions : clusteredOptions;
                liveOpenSource = new GeoJsonSource(sourcePrefix + "open", FeatureCollection.fromFeatures(new Feature[]{}), opts);
                liveWepSource = new GeoJsonSource(sourcePrefix + "wep", FeatureCollection.fromFeatures(new Feature[]{}), opts);
                liveWpaSource = new GeoJsonSource(sourcePrefix + "wpa", FeatureCollection.fromFeatures(new Feature[]{}), opts);
                liveBtSource = new GeoJsonSource(sourcePrefix + "bt", FeatureCollection.fromFeatures(new Feature[]{}), opts);
                liveCellSource = new GeoJsonSource(sourcePrefix + "cell", FeatureCollection.fromFeatures(new Feature[]{}), opts);

            // If style is already loaded, create sources/layers now. Otherwise register listener to add them when style loads.
            if (map.getStyle() != null) {
                Logging.info("MapRender: style present at init, adding sources/layers");
                ensureSourcesAndLayers();
            } else {
                // Try to register style-loaded listener via reflection to support multiple SDK versions.
                boolean listenerRegistered = false;
                try {
                    java.lang.Class<?> mapClass = map.getClass();
                    java.lang.reflect.Method addListener = null;
                    for (java.lang.reflect.Method mm : mapClass.getMethods()) {
                        if ("addOnStyleLoadedListener".equals(mm.getName()) && mm.getParameterCount() == 1) {
                            addListener = mm;
                            break;
                        }
                    }
                    if (addListener != null) {
                        final java.lang.reflect.Method listenerMethod = addListener;
                        final java.lang.Class<?> listenerType = listenerMethod.getParameterTypes()[0];
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                listenerType.getClassLoader(),
                                new java.lang.Class[]{listenerType},
                                (proxyObj, method, args) -> {
                                    try {
                                        Logging.info("MapRender: style loaded (reflection), adding sources/layers");
                                        ensureSourcesAndLayers();
                                    } catch (Exception ex) {
                                        Logging.error("MapRender: failed in reflected style listener: " + ex, ex);
                                    }
                                    return null;
                                }
                        );
                        listenerMethod.invoke(map, proxy);
                        Logging.info("MapRender: registered reflected style listener");
                        listenerRegistered = true;
                    }
                } catch (Throwable t) {
                    Logging.error("MapRender: reflection style listener registration failed: " + t, t);
                }

                if (!listenerRegistered) {
                    Logging.info("MapRender: style listener not available, will poll until style loads");
                    final Handler styleHandler = new Handler(Looper.getMainLooper());
                    final Runnable checker = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (map.getStyle() != null) {
                                    Logging.info("MapRender: style loaded (poll), adding sources/layers");
                                    ensureSourcesAndLayers();
                                } else {
                                    styleHandler.postDelayed(this, 250);
                                }
                            } catch (Exception ex) {
                                Logging.error("MapRender: style check failed: " + ex, ex);
                            }
                        }
                    };
                    styleHandler.post(checker);
                }
            }
        } catch (Exception ex) {
            Logging.error("MapRender: failed to init live sources: " + ex, ex);
        }

        reCluster();
    }

    private void addLatestNetworks() {
        // add items
        int cached = 0;
        int added = 0;
        final Collection<Network> nets = MainActivity.getNetworkCache().values();

        for (final Network network : nets) {
            cached++;
            if (okForMapTab(network)) {
                added++;
                // add via our GeoJSON source lists
                addItem(network);
            }
        }
        Logging.info("MapRender cached: " + cached + " added: " + added);
        // addItem already increments networkCount
    }

    public boolean okForMapTab( final Network network ) {
        final boolean hideNets = prefs.getBoolean( PreferenceKeys.PREF_MAP_HIDE_NETS, false );
        final boolean showNewDBOnly = prefs.getBoolean( PreferenceKeys.PREF_MAP_ONLY_NEWDB, false )
                && ! isDbResult;
        if (network.getPosition() != null && !hideNets) {
            if (!showNewDBOnly || network.isNew()) {
                return FilterMatcher.isOk(ssidMatcher,
                        null /*ALIBI: we *can* use the filter from the list filter view here ...*/,
                        prefs, MappingFragment.MAP_DIALOG_PREFIX, network);
            }
        }
        return false;
    }

    public void addItem(final Network network) {
        if (network.getPosition() != null) {
            final int count = networkCount.incrementAndGet();
            if (count > MainActivity.getNetworkCache().size() * 1.3) {
                // getting too large, re-sync with cache
                reCluster();
            }
            else {
                // add feature to appropriate source (open / wep / wpa)
                try {
                    Feature f = Feature.fromGeometry(Point.fromLngLat(network.getLatLng().getLongitude(), network.getLatLng().getLatitude()));
                    f.addStringProperty("bssid", network.getBssid());
                    f.addStringProperty("ssid", network.getSsid());
                    f.addNumberProperty("type", (double) network.getType().ordinal());
                    f.addNumberProperty("crypto", (double) network.getCrypto());
                    final int crypto = network.getCrypto();
                    Logging.info("MapRender: adding feature bssid=" + network.getBssid() + " crypto=" + crypto);
                    // handle BT / BLE and cellular first
                    if (NetworkType.isBtType(network.getType())) {
                        liveBtFeatures.add(f);
                    } else if (NetworkType.isCellType(network.getType())) {
                        liveCellFeatures.add(f);
                    } else {
                        if (crypto == Network.CRYPTO_NONE) {
                            liveOpenFeatures.add(f);
                        } else if (crypto == Network.CRYPTO_WEP) {
                            liveWepFeatures.add(f);
                        } else {
                            // WPA / WPA2 / WPA3 / other secure
                            liveWpaFeatures.add(f);
                        }
                    }
                    updateSource();
                } catch (Exception ignored) {}
            }
        }
    }

    public void clear() {
        // Don't clear DB-result features: these are intended to persist for the DB results view
        if (isDbResult) {
            Logging.info("MapRender: clear skipped for db results");
            return;
        }
        Logging.info("MapRender: clear");
        labeledNetworks.clear();
        networkCount.set(0);
        liveOpenFeatures.clear();
        liveWepFeatures.clear();
        liveBtFeatures.clear();
        liveCellFeatures.clear();
        liveWpaFeatures.clear();
        updateSource();
        // mClusterManager.setRenderer(networkRenderer);
    }

    public void reCluster() {
        // For DB results, avoid reclustering which would clear stored DB features
        if (isDbResult) {
            Logging.info("MapRender: reCluster skipped for db results");
            return;
        }
        Logging.info("MapRender: reCluster");
        clear();
        if (!isDbResult) {
            addLatestNetworks();
        }
    }

    private void updateSource() {
        try {
            if (liveOpenSource != null) {
                synchronized (liveOpenFeatures) {
                    Logging.info("MapRender: instance=" + System.identityHashCode(this) + " updating " + sourcePrefix + "open source, features=" + liveOpenFeatures.size() + " mapInstance=" + System.identityHashCode(map));
                    liveOpenSource.setGeoJson(FeatureCollection.fromFeatures(liveOpenFeatures));
                }
            } else {
                Logging.info("MapRender: " + sourcePrefix + "open source is null when updating");
            }
            if (liveWepSource != null) {
                synchronized (liveWepFeatures) {
                    Logging.info("MapRender: instance=" + System.identityHashCode(this) + " updating " + sourcePrefix + "wep source, features=" + liveWepFeatures.size() + " mapInstance=" + System.identityHashCode(map));
                    liveWepSource.setGeoJson(FeatureCollection.fromFeatures(liveWepFeatures));
                }
            }
            if (liveWpaSource != null) {
                synchronized (liveWpaFeatures) {
                    Logging.info("MapRender: instance=" + System.identityHashCode(this) + " updating " + sourcePrefix + "wpa source, features=" + liveWpaFeatures.size() + " mapInstance=" + System.identityHashCode(map));
                    liveWpaSource.setGeoJson(FeatureCollection.fromFeatures(liveWpaFeatures));
                }
            }
            if (liveBtSource != null) {
                synchronized (liveBtFeatures) {
                    Logging.info("MapRender: instance=" + System.identityHashCode(this) + " updating " + sourcePrefix + "bt source, features=" + liveBtFeatures.size() + " mapInstance=" + System.identityHashCode(map));
                    liveBtSource.setGeoJson(FeatureCollection.fromFeatures(liveBtFeatures));
                }
            }
            if (liveCellSource != null) {
                synchronized (liveCellFeatures) {
                    Logging.info("MapRender: instance=" + System.identityHashCode(this) + " updating " + sourcePrefix + "cell source, features=" + liveCellFeatures.size() + " mapInstance=" + System.identityHashCode(map));
                    liveCellSource.setGeoJson(FeatureCollection.fromFeatures(liveCellFeatures));
                }
            }
        } catch (Exception ex) {
            Logging.error("MapRender: failed updating source: " + ex, ex);
        }
    }

    // create layers and add sources to the style; safe to call multiple times
    private void ensureSourcesAndLayers() {
        if (map.getStyle() == null) return;
        // avoid re-adding sources/layers if already present
            try {
                if (map.getStyle().getSourceAs(sourcePrefix + "open") != null) {
                    Logging.info("MapRender: sources/layers already present, skipping");
                    return;
                }
            } catch (Exception ignored) {}
        try {
            map.getStyle().addSource(liveOpenSource);
            map.getStyle().addSource(liveWepSource);
            map.getStyle().addSource(liveWpaSource);
            map.getStyle().addSource(liveBtSource);
            map.getStyle().addSource(liveCellSource);

            // unclustered circles (single points) for each type
                CircleLayer openUnclustered = new CircleLayer(sourcePrefix + "open_unclustered", sourcePrefix + "open")
                    .withProperties(PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#4CAF50")); // green
            openUnclustered.setFilter(Expression.neq(Expression.get("cluster"), true));
            map.getStyle().addLayer(openUnclustered);

                CircleLayer wepUnclustered = new CircleLayer(sourcePrefix + "wep_unclustered", sourcePrefix + "wep")
                    .withProperties(PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#FF9800")); // orange
            wepUnclustered.setFilter(Expression.neq(Expression.get("cluster"), true));
            map.getStyle().addLayer(wepUnclustered);

                CircleLayer wpaUnclustered = new CircleLayer(sourcePrefix + "wpa_unclustered", sourcePrefix + "wpa")
                    .withProperties(PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#F44336")); // red
            wpaUnclustered.setFilter(Expression.neq(Expression.get("cluster"), true));
            map.getStyle().addLayer(wpaUnclustered);

                CircleLayer btUnclustered = new CircleLayer(sourcePrefix + "bt_unclustered", sourcePrefix + "bt")
                    .withProperties(PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#2196F3")); // blue
            btUnclustered.setFilter(Expression.neq(Expression.get("cluster"), true));
            map.getStyle().addLayer(btUnclustered);

                CircleLayer cellUnclustered = new CircleLayer(sourcePrefix + "cell_unclustered", sourcePrefix + "cell")
                    .withProperties(PropertyFactory.circleRadius(6f), PropertyFactory.circleColor("#9C27B0")); // purple
            cellUnclustered.setFilter(Expression.neq(Expression.get("cluster"), true));
            map.getStyle().addLayer(cellUnclustered);

            // labels and cluster layers follow same logic as before; for brevity reuse updateSource to populate
            Logging.info("MapRender: added sources and base layers");
            try {
                int layerCount = map.getStyle().getLayers().size();
                Logging.info("MapRender: style has " + layerCount + " layers after add");
                for (String id : new String[]{sourcePrefix + "open_unclustered",sourcePrefix + "wep_unclustered",sourcePrefix + "wpa_unclustered",sourcePrefix + "bt_unclustered",sourcePrefix + "cell_unclustered"}) {
                    boolean present = map.getStyle().getLayer(id) != null;
                    Logging.info("MapRender: layer present check " + id + " = " + present);
                }
            } catch (Exception ex) {
                Logging.error("MapRender: error inspecting layers: " + ex, ex);
            }
            // After creating sources/layers, ensure any queued features are applied to the sources
            try {
                updateSource();
                Logging.info("MapRender: updateSource() called after adding sources/layers");
            } catch (Exception ex) {
                Logging.error("MapRender: error updating sources after layer add: " + ex, ex);
            }
        } catch (Exception ex) {
            Logging.error("MapRender: error adding sources/layers: " + ex, ex);
        }
    }

    public void onResume() {
        ssidMatcher = FilterMatcher.getSsidFilterMatcher( prefs, MappingFragment.MAP_DIALOG_PREFIX );
        reCluster();
    }

    public void updateNetwork(final Network network) {
        if (okForMapTab(network)) {
            sendUpdateNetwork(network.getBssid());
        }
    }

    private void sendUpdateNetwork(final String bssid) {
        final Bundle data = new Bundle();
        data.putString(MESSAGE_BSSID, bssid);
        Message message = new Message();
        message.setData(data);
        updateMarkersHandler.sendMessage(message);
    }

    private void sendUpdateNetwork(final ArrayList<String> bssids) {
        final Bundle data = new Bundle();
        data.putStringArrayList(MESSAGE_BSSID_LIST, bssids);
        Message message = new Message();
        message.setData(data);
        updateMarkersHandler.sendMessage(message);
    }

    final Handler updateMarkersHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(final Message message) {
            final String bssid = message.getData().getString(MESSAGE_BSSID);
            if (bssid != null) {
                final Network network = MainActivity.getNetworkCache().get(bssid);
                if (network != null) {
                    // remove old feature and re-add updated feature
                    removeFeatureByBssid(bssid);
                    addItem(network);
                }
            }

            final ArrayList<String> bssids = message.getData().getStringArrayList(MESSAGE_BSSID_LIST);
            if (bssids != null && !bssids.isEmpty()) {
                Logging.info("bssids: " + bssids.size());
                for (final String thisBssid : bssids) {
                    final Network network = MainActivity.getNetworkCache().get(thisBssid);
                    if (network != null) {
                        removeFeatureByBssid(thisBssid);
                        addItem(network);
                    }
                }
            }
        }
    };

    private void removeFeatureByBssid(final String bssid) {
        try {
            synchronized (liveOpenFeatures) {
                liveOpenFeatures.removeIf(f -> bssid.equals(f.getStringProperty("bssid")));
            }
            synchronized (liveWepFeatures) {
                liveWepFeatures.removeIf(f -> bssid.equals(f.getStringProperty("bssid")));
            }
            synchronized (liveWpaFeatures) {
                liveWpaFeatures.removeIf(f -> bssid.equals(f.getStringProperty("bssid")));
            }
            synchronized (liveBtFeatures) {
                liveBtFeatures.removeIf(f -> bssid.equals(f.getStringProperty("bssid")));
            }
            synchronized (liveCellFeatures) {
                liveCellFeatures.removeIf(f -> bssid.equals(f.getStringProperty("bssid")));
            }
            updateSource();
        } catch (Exception ex) {
            Logging.error("MapRender: failed removing feature by bssid: " + ex, ex);
        }
    }

    /**
     * Set route geojson (FeatureCollection) to display as a line on the map.
     */
    public void setRouteGeoJson(final FeatureCollection fc) {
        try {
            if (map != null && map.getStyle() != null) {
                final GeoJsonSource rs = (GeoJsonSource) map.getStyle().getSourceAs("route_source");
                if (rs != null) {
                    rs.setGeoJson(fc == null ? FeatureCollection.fromFeatures(new Feature[]{}) : fc);
                }
            }
        } catch (Exception ex) {
            Logging.error("MapRender: failed setting route geojson: " + ex, ex);
        }
    }

    private static final class NetworkHues {
        public static final float WIFI = 92.90f;     // #87A96B
        public static final float WIFI_NEW = 97.67f; // #85BB65
        public static final float BT = 220.9f;        // #4682B
        public static final float BT_NEW = 210.88f;  // #99BADD
        public static final float CELL = 0.0f;       // #B22222
        public static final float CELL_NEW = 4.8f;   // #E34234
        public static final float DEFAULT = 110.0f;  // TODO: establish a better default
        public static final float NEW = 120.0f;      // TODO: ""
    }
}
