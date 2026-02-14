package net.wigle.wigleandroid;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.graphics.Color;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;
import android.graphics.PointF;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import android.widget.LinearLayout;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.ListFragment;
import android.widget.Button;
import android.view.View;
import org.maplibre.android.style.layers.Layer;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.util.Log;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.lang.StringBuilder;

public class VectorMapActivity extends AppCompatActivity {
    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Style loadedStyle;
    private GeoJsonSource wifiSource;
    private GeoJsonSource btSource;
    private GeoJsonSource cellSource;
    private FloatingActionButton fabLocate;
    private View layerButtonBar;
    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private boolean trackingEnabled = false;
    private int currentInsetTop = 0;
    private int currentInsetBottom = 0;
    private int fabOriginalBottomMargin = 0;
    private int layerBarOriginalTopMargin = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize MapLibre
        MapLibre.getInstance(this);

        setContentView(R.layout.activity_vector_map);

        mapView = findViewById(R.id.mapView);
        fabLocate = findViewById(R.id.btn_locate);
        layerButtonBar = findViewById(R.id.layer_button_bar);

        // capture original top margin for layer bar so we can shift it below status bar
        ViewGroup.LayoutParams lbLp = layerButtonBar != null ? layerButtonBar.getLayoutParams() : null;
        if (lbLp instanceof MarginLayoutParams) {
            layerBarOriginalTopMargin = ((MarginLayoutParams) lbLp).topMargin;
        }

        // capture original bottom margin of FAB
        ViewGroup.LayoutParams lp = fabLocate.getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            fabOriginalBottomMargin = ((MarginLayoutParams) lp).bottomMargin;
        }
        // adjust UI for system window insets (navigation bar / status bar)
        View root = findViewById(R.id.root_container);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, new androidx.core.view.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                    Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    currentInsetTop = sys.top;
                    currentInsetBottom = sys.bottom;
                    // push layer button bar down from status bar by adjusting its top margin
                    try {
                        if (layerButtonBar != null) {
                            ViewGroup.LayoutParams lp2 = layerButtonBar.getLayoutParams();
                            if (lp2 instanceof MarginLayoutParams) {
                                MarginLayoutParams mlp2 = (MarginLayoutParams) lp2;
                                mlp2.topMargin = layerBarOriginalTopMargin + currentInsetTop;
                                layerButtonBar.setLayoutParams(mlp2);
                            }
                        }
                    } catch (Exception ignored) {}
                    // adjust FAB margin to sit above nav bar
                    try {
                        ViewGroup.LayoutParams lp = fabLocate.getLayoutParams();
                        if (lp instanceof MarginLayoutParams) {
                            MarginLayoutParams mlp = (MarginLayoutParams) lp;
                            mlp.bottomMargin = fabOriginalBottomMargin + currentInsetBottom;
                            fabLocate.setLayoutParams(mlp);
                        }
                    } catch (Exception ignored) {}
                    // update map padding if map is ready — post to ensure layer bar measured
                    try {
                        if (mapLibreMap != null) {
                            final int topInset = currentInsetTop;
                            final int bottomInset = currentInsetBottom;
                            if (layerButtonBar != null) {
                                layerButtonBar.post(() -> {
                                    int layerBarHeight = layerButtonBar.getHeight();
                                    try { mapLibreMap.setPadding(0, topInset + layerBarHeight, 0, bottomInset); } catch (Exception ignored) {}
                                });
                            } else {
                                try { mapLibreMap.setPadding(0, topInset, 0, bottomInset); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                    return insets;
                }
            });
        }
        fabLocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTracking();
            }
        });
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
                VectorMapActivity.this.mapLibreMap = mapLibreMap;
                try {
                    final String styleUrl = "https://tiles.wifidb.net/styles/WDB_OSM/style.json";
                    mapLibreMap.setStyle(styleUrl, new Style.OnStyleLoaded() {
                        @Override
                        public void onStyleLoaded(@NonNull Style style) {
                            loadedStyle = style;
                            // default camera until we get location
                            mapLibreMap.setCameraPosition(new CameraPosition.Builder()
                                    .target(new LatLng(37.0, -122.0))
                                    .zoom(10.0)
                                    .build());
                            attemptEnableLocationComponent();
                            // add live GeoJSON sources and layers
                            try {
                                // wifi source + layers by security
                                wifiSource = new GeoJsonSource("live_wifi", FeatureCollection.fromFeatures(new Feature[]{}));
                                style.addSource(wifiSource);
                                CircleLayer wifiOpen = new CircleLayer("live_wifi_open_layer", "live_wifi");
                                wifiOpen.setProperties(
                                    PropertyFactory.circleColor(Color.parseColor("#4CAF50")),
                                    PropertyFactory.circleRadius(9f),
                                    PropertyFactory.circleOpacity(0.9f)
                                );
                                   wifiOpen.setFilter(Expression.any(
                                       Expression.eq(Expression.get("security"), Expression.literal("open")),
                                       Expression.eq(Expression.get("sectype"), Expression.literal(1)),
                                       Expression.eq(Expression.get("sectype"), Expression.literal("1"))
                                   ));
                                style.addLayer(wifiOpen);

                                CircleLayer wifiWep = new CircleLayer("live_wifi_wep_layer", "live_wifi");
                                wifiWep.setProperties(
                                    PropertyFactory.circleColor(Color.parseColor("#FF9800")),
                                    PropertyFactory.circleRadius(9f),
                                    PropertyFactory.circleOpacity(0.9f)
                                );
                                   wifiWep.setFilter(Expression.any(
                                       Expression.eq(Expression.get("security"), Expression.literal("wep")),
                                       Expression.eq(Expression.get("sectype"), Expression.literal(2)),
                                       Expression.eq(Expression.get("sectype"), Expression.literal("2"))
                                   ));
                                style.addLayer(wifiWep);

                                CircleLayer wifiSecure = new CircleLayer("live_wifi_secure_layer", "live_wifi");
                                wifiSecure.setProperties(
                                    PropertyFactory.circleColor(Color.parseColor("#F44336")),
                                    PropertyFactory.circleRadius(9f),
                                    PropertyFactory.circleOpacity(0.9f)
                                );
                                   wifiSecure.setFilter(Expression.any(
                                       Expression.eq(Expression.get("security"), Expression.literal("secure")),
                                       Expression.eq(Expression.get("sectype"), Expression.literal(3)),
                                       Expression.eq(Expression.get("sectype"), Expression.literal("3"))
                                   ));
                                style.addLayer(wifiSecure);

                                // bluetooth source + layer (blue)
                                btSource = new GeoJsonSource("live_bt", FeatureCollection.fromFeatures(new Feature[]{}));
                                style.addSource(btSource);
                                CircleLayer btLayer = new CircleLayer("live_bt_layer", "live_bt");
                                btLayer.setProperties(
                                    PropertyFactory.circleColor(Color.parseColor("#2196F3")),
                                    PropertyFactory.circleRadius(9f),
                                    PropertyFactory.circleOpacity(0.9f)
                                );
                                style.addLayer(btLayer);

                                // cell source + layer (purple)
                                cellSource = new GeoJsonSource("live_cell", FeatureCollection.fromFeatures(new Feature[]{}));
                                style.addSource(cellSource);
                                CircleLayer cellLayer = new CircleLayer("live_cell_layer", "live_cell");
                                cellLayer.setProperties(
                                    PropertyFactory.circleColor(Color.parseColor("#9C27B0")),
                                        PropertyFactory.circleRadius(9f),
                                        PropertyFactory.circleOpacity(0.9f)
                                );
                                style.addLayer(cellLayer);
                            } catch (Exception ex) {
                                // ignore layer creation failures
                            }
                            // ensure live layers are above any pre-existing style layers
                            try { bringLiveLayersToTop(); } catch (Exception ignored) {}
                            // apply any saved insets to map padding now that style is loaded
                            try {
                                int layerBarHeight = (layerButtonBar != null) ? layerButtonBar.getHeight() : 0;
                                if (currentInsetTop > 0 || currentInsetBottom > 0) {
                                    mapLibreMap.setPadding(0, currentInsetTop + layerBarHeight, 0, currentInsetBottom);
                                }
                            } catch (Exception ignored) {}
                            // add click listener to show details for features
                            try {
                                mapLibreMap.addOnMapClickListener(new MapLibreMap.OnMapClickListener() {
                                    @Override
                                    public boolean onMapClick(@NonNull LatLng point) {
                                        try {
                                            PointF screenPt = mapLibreMap.getProjection().toScreenLocation(point);
                                            List<Feature> features = mapLibreMap.queryRenderedFeatures(screenPt,
                                                    "live_wifi_open_layer", "live_wifi_wep_layer", "live_wifi_secure_layer",
                                                    "live_bt_layer", "live_cell_layer");
                                            if (features == null || features.isEmpty()) return false;
                                            Set<String> seen = new HashSet<>();
                                            StringBuilder out = new StringBuilder();
                                            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                                            for (Feature f : features) {
                                                if (f == null) continue;
                                                String fid = null;
                                                String ftype = null;
                                                String fsignal = null;
                                                try {
                                                    if (f.hasProperty("id")) fid = f.getProperty("id").getAsString();
                                                } catch (Exception ex) {
                                                    try { fid = f.getStringProperty("id"); } catch (Exception ignored) {}
                                                }
                                                try {
                                                    if (f.hasProperty("type")) ftype = f.getProperty("type").getAsString();
                                                } catch (Exception ex) {
                                                    try { ftype = f.getStringProperty("type"); } catch (Exception ignored) {}
                                                }
                                                try {
                                                    if (f.hasProperty("signal")) fsignal = f.getProperty("signal").getAsString();
                                                } catch (Exception ex) {
                                                    try { fsignal = f.getStringProperty("signal"); } catch (Exception ignored) {}
                                                }
                                                if (fid == null) {
                                                    fid = (ftype == null ? "feat" : ftype) + "-" + (fsignal == null ? "" : fsignal) + "-" + f.hashCode();
                                                }
                                                if (seen.contains(fid)) continue;
                                                seen.add(fid);

                                                Network net = null;
                                                if (MainActivity.getNetworkCache() != null) {
                                                    net = MainActivity.getNetworkCache().get(fid.toLowerCase(Locale.US));
                                                }

                                                if (net != null) {
                                                    out.append("SSID: ").append(net.getSsid()).append('\n');
                                                    out.append("Mac: ").append(net.getBssid()).append('\n');
                                                    Integer ch = net.getChannel();
                                                    if (ch != null) out.append("Channel: ").append(ch).append('\n');
                                                    out.append("Auth/Capabilities: ").append(net.getShowCapabilities()).append('\n');
                                                    String oui = net.getOui(ListFragment.lameStatic.oui);
                                                    if (oui != null && !oui.isEmpty()) out.append("Manufacturer: ").append(oui).append('\n');
                                                    out.append("Network Type: ").append(net.getType()).append('\n');
                                                    out.append("Signal: ").append(net.getLevel()).append('\n');
                                                    if (net.getLatLng() != null) {
                                                        out.append("Latitude: ").append(net.getLatLng().latitude).append('\n');
                                                        out.append("Longitude: ").append(net.getLatLng().longitude).append('\n');
                                                    }
                                                    Long lt = net.getLastTime();
                                                    if (lt != null) out.append("Last: ").append(df.format(new Date(lt))).append('\n');
                                                } else {
                                                    // fallback to showing properties from the feature
                                                    out.append("Type: ").append(ftype == null ? "" : ftype).append('\n');
                                                    out.append("ID: ").append(fid).append('\n');
                                                    out.append("Signal: ").append(fsignal == null ? "" : fsignal).append('\n');
                                                    if (f.geometry() != null && f.geometry() instanceof Point) {
                                                        Point p = (Point) f.geometry();
                                                        out.append("Latitude: ").append(p.latitude()).append('\n');
                                                        out.append("Longitude: ").append(p.longitude()).append('\n');
                                                    }
                                                }
                                                out.append('\n');
                                            }

                                            // show dialog
                                            if (out.length() > 0) {
                                                new androidx.appcompat.app.AlertDialog.Builder(VectorMapActivity.this)
                                                        .setTitle("Details")
                                                        .setMessage(out.toString())
                                                        .setPositiveButton("OK", null)
                                                        .show();
                                            }
                                        } catch (Exception ex) {
                                            // ignore click errors
                                        }
                                        return true;
                                    }
                                });
                            } catch (Exception ex) {
                                // ignore listener attach errors
                            }
                            // refresh button labels now that style and layers are loaded
                            refreshLayerButtonLabels();
                            // dump style summary for debugging
                            logStyleSummary();
                        }
                    });
                } catch (Exception ex) {
                    // log but avoid crashing during style load
                }
            }
        });
    }

    private void logStyleSummary() {
        try {
            if (loadedStyle == null) {
                Log.i("VectorMapActivity", "Style not loaded for summary");
                return;
            }
            boolean hasWifiDB = loadedStyle.getSource("WifiDB") != null;
            boolean hasWifiDBNewest = loadedStyle.getSource("WifiDB_newest") != null;
            boolean hasWifiDBCells = loadedStyle.getSource("WifiDB_cells") != null;
            boolean hasDailys = loadedStyle.getSource("dailys") != null;
            boolean hasLatests = loadedStyle.getSource("latests") != null;
            Log.i("VectorMapActivity", "Style summary: sources present - WifiDB=" + hasWifiDB + " WifiDB_newest=" + hasWifiDBNewest + " WifiDB_cells=" + hasWifiDBCells + " dailys=" + hasDailys + " latests=" + hasLatests);
            List<Layer> all = loadedStyle.getLayers();
            Log.i("VectorMapActivity", "Style has " + (all == null ? 0 : all.size()) + " layers. Listing up to 40 ids:");
            if (all != null) {
                int c = 0;
                for (Layer L : all) {
                    try {
                        Log.i("VectorMapActivity", " layer[" + c + "] id=" + L.getId());
                    } catch (Exception ignored) {}
                    c++;
                    if (c > 40) break;
                }
            }
        } catch (Exception ex) {
            Log.i("VectorMapActivity", "Failed to log style summary: " + ex.getMessage());
        }
    }

    /**
     * Add a layer but prefer to insert it below the live layers so live points remain on top.
     */
    private void addLayerBelowLive(Layer layer) {
        try {
            if (loadedStyle == null || layer == null) return;
            // prefer to insert server layers below the main live wifi open layer
            String ref = "live_wifi_open_layer";
            if (loadedStyle.getLayer(ref) != null) {
                try { loadedStyle.addLayerBelow(layer, ref); return; } catch (Exception ignored) {}
            }
            // fallback: add normally
            loadedStyle.addLayer(layer);
        } catch (Exception ignored) {}
    }

    /**
     * Ensure the live layers are on top by removing and re-adding them (which appends them).
     */
    private void bringLiveLayersToTop() {
        if (loadedStyle == null) return;
        String[] liveIds = new String[]{"live_wifi_open_layer", "live_wifi_wep_layer", "live_wifi_secure_layer", "live_bt_layer", "live_cell_layer"};
        // first, re-add our explicit live layers to top
        for (String id : liveIds) {
            try {
                Layer L = loadedStyle.getLayer(id);
                if (L == null) continue;
                try { loadedStyle.removeLayer(id); } catch (Exception ignored) {}
                try { loadedStyle.addLayer(L); } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }

        // Avoid removing MapLibre-created location/pulsing layers (removing them can break the location component).
        // Instead, detect any dynamic location-like layer ids and, if found, re-insert our live layers above the last detected id.
        try {
            List<String> dynamicIds = new java.util.ArrayList<>();
            List<Layer> all = loadedStyle.getLayers();
            if (all != null) {
                for (Layer L : all) {
                    try {
                        String lid = L.getId();
                        if (lid == null) continue;
                        String ll = lid.toLowerCase(Locale.US);
                        if (ll.contains("location") || ll.contains("puls") || ll.contains("pulse") || ll.contains("indicator") || ll.contains("compass") || ll.contains("location-indicator") || ll.contains("layer-location")) {
                            dynamicIds.add(lid);
                        }
                    } catch (Exception ignored) {}
                }
            }
            // If we found any dynamic location-like layer ids, try to place our live layers above the last one so they render on top.
            if (!dynamicIds.isEmpty()) {
                String anchor = dynamicIds.get(dynamicIds.size() - 1);
                for (String id : liveIds) {
                    try {
                        Layer L = loadedStyle.getLayer(id);
                        if (L == null) continue;
                        try { loadedStyle.removeLayer(id); } catch (Exception ignored) {}
                        try { loadedStyle.addLayerAbove(L, anchor); } catch (Exception ex) { try { loadedStyle.addLayer(L); } catch (Exception ignored) {} }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Add a server layer in the configured bottom-to-top order so older layers remain below newer ones.
     * logicalTag should be the logical layer identifier (e.g. "WifiDB_Legacy", "WifiDB_monthly", "dailys", "cell_networks").
     */
    private void addServerLayerInOrder(Layer layer, String logicalTag) {
        try {
            if (loadedStyle == null || layer == null) return;
            // bottom -> top order
            String[] order = new String[]{"WifiDB_Legacy", "WifiDB_2to3year", "WifiDB_1to2year", "WifiDB_0to1year", "WifiDB_monthly", "WifiDB_weekly", "dailys", "latests", "cell_networks"};
            String tagLower = logicalTag == null ? "" : logicalTag.toLowerCase(Locale.US);
            int idx = -1;
            for (int i = 0; i < order.length; i++) {
                String o = order[i];
                if (tagLower.contains(o.toLowerCase(Locale.US)) || o.equalsIgnoreCase(logicalTag)) { idx = i; break; }
            }
            // fallback heuristics
            if (idx == -1) {
                if (tagLower.contains("daily") || tagLower.contains("day") || tagLower.contains("dailys")) idx = 6;
                else if (tagLower.contains("week")) idx = 5;
                else if (tagLower.contains("month")) idx = 4;
                else if (tagLower.contains("year") || tagLower.contains("0to1") || tagLower.contains("latest") || tagLower.contains("newest")) idx = 3;
                else if (tagLower.contains("cell")) idx = 8;
            }

            // try to find an existing layer that should be above this one (higher index) and insert below it
            if (idx >= 0) {
                for (int j = idx + 1; j < order.length; j++) {
                    String want = order[j].toLowerCase(Locale.US);
                    for (Layer L : loadedStyle.getLayers()) {
                        try {
                            String lid = L.getId();
                            if (lid != null && lid.toLowerCase(Locale.US).contains(want)) {
                                try { loadedStyle.addLayerBelow(layer, lid); return; } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }
                }
                // otherwise try to insert above a lower-index layer
                for (int j = idx - 1; j >= 0; j--) {
                    String want = order[j].toLowerCase(Locale.US);
                    for (Layer L : loadedStyle.getLayers()) {
                        try {
                            String lid = L.getId();
                            if (lid != null && lid.toLowerCase(Locale.US).contains(want)) {
                                try { loadedStyle.addLayerAbove(layer, lid); return; } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            // no helpful anchors found — fall back to placing below live layers
            addLayerBelowLive(layer);
        } catch (Exception ignored) {}
    }

    /**
     * Create a GeoJSON source from a remote URL and add a CircleLayer styled for server-side historic points.
     * The source id will be `srv_<layerId>` and layer id `srv_<layerId>_layer`.
     */
    private void ensureServerGeoJsonLayer(String layerId) {
        if (loadedStyle == null) return;
        Log.i("VectorMapActivity", "ensureServerGeoJsonLayer called for: " + layerId);
        try {
            final String srcId = "srv_" + layerId;
            final String layerName = srcId + "_layer";
            if (loadedStyle.getSource(srcId) != null) return; // already added

            // Use the v1 GeoJSON API on wifidb.net for these server layers; do not accept arbitrary full-URLs.
            String geojsonUrl;
            String base = "https://wifidb.net";
            String lower = layerId.toLowerCase(Locale.US);

            // If the style already contains a vector source for WifiDB cells, create a vector-backed layer
            try {
                if (loadedStyle.getSource("WifiDB_cells") != null && (layerId.equalsIgnoreCase("WifiDB_cells") || layerId.equalsIgnoreCase("cell_networks") || lower.contains("cell"))) {
                    // create a circle layer that uses the existing vector source and source-layer 'cell_networks'
                    CircleLayer vecLay = new CircleLayer(layerName, "WifiDB_cells");
                    try { vecLay.setSourceLayer("cell_networks"); } catch (Exception ignored) {}
                    vecLay.setProperties(
                            PropertyFactory.circleColor(Color.parseColor("#885FCD")),
                            PropertyFactory.circleRadius(2.25f),
                            PropertyFactory.circleOpacity(1f),
                            PropertyFactory.circleBlur(0.5f)
                    );
                    addServerLayerInOrder(vecLay, layerId);
                    Log.i("VectorMapActivity", "Added vector-backed cell layer for: " + layerId);
                    bringLiveLayersToTop();
                    return;
                }
            } catch (Exception ignored) {}

            // If the style contains vector sources for APs (WifiDB or WifiDB_newest), add matching vector-backed layers
            try {
                // mapping of known AP layers to colors/radii (based on PHP CreateApLayer calls)
                if (loadedStyle.getSource("WifiDB") != null) {
                    if (layerId.equalsIgnoreCase("WifiDB_Legacy") || layerId.equalsIgnoreCase("WifiDB_2to3year") || layerId.equalsIgnoreCase("WifiDB_1to2year")) {
                        String lidLower = layerId.toLowerCase(Locale.US);
                        String openColor = "#00802b";
                        String wepColor = "#cc7a00";
                        String secureColor = "#b30000";
                        float radius = 3f;
                        if (lidLower.contains("2to3")) { openColor = "#00b33c"; wepColor = "#e68a00"; secureColor = "#cc0000"; radius = 2.75f; }
                        else if (lidLower.contains("1to2")) { openColor = "#00e64d"; wepColor = "#ff9900"; secureColor = "#e60000"; radius = 2.5f; }

                        CircleLayer openL = new CircleLayer(layerName + "_open", "WifiDB");
                        try { openL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        openL.setProperties(PropertyFactory.circleColor(Color.parseColor(openColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        openL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("open")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(1)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("1"))
                        ));
                        addServerLayerInOrder(openL, layerId);

                        CircleLayer wepL = new CircleLayer(layerName + "_wep", "WifiDB");
                        try { wepL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        wepL.setProperties(PropertyFactory.circleColor(Color.parseColor(wepColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        wepL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("wep")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(2)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("2"))
                        ));
                        addServerLayerInOrder(wepL, layerId);

                        CircleLayer secL = new CircleLayer(layerName + "_secure", "WifiDB");
                        try { secL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        secL.setProperties(PropertyFactory.circleColor(Color.parseColor(secureColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        secL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("secure")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(3)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("3"))
                        ));
                        addServerLayerInOrder(secL, layerId);
                        Log.i("VectorMapActivity", "Added vector-backed WifiDB layer for: " + layerId);
                        bringLiveLayersToTop();
                        return;
                    }
                }
                if (loadedStyle.getSource("WifiDB_newest") != null) {
                    if (layerId.equalsIgnoreCase("WifiDB_0to1year") || layerId.equalsIgnoreCase("WifiDB_monthly") || layerId.equalsIgnoreCase("WifiDB_weekly") || layerId.equalsIgnoreCase("WifiDB_0to1")) {
                        String openColor = "#1aff66";
                        String wepColor = "#ffad33";
                        String secureColor = "#ff1a1a";
                        float radius = 2f;

                        CircleLayer openL = new CircleLayer(layerName + "_open", "WifiDB_newest");
                        try { openL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        openL.setProperties(PropertyFactory.circleColor(Color.parseColor(openColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        openL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("open")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(1)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("1"))
                        ));
                        addServerLayerInOrder(openL, layerId);

                        CircleLayer wepL = new CircleLayer(layerName + "_wep", "WifiDB_newest");
                        try { wepL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        wepL.setProperties(PropertyFactory.circleColor(Color.parseColor(wepColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        wepL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("wep")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(2)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("2"))
                        ));
                        addServerLayerInOrder(wepL, layerId);

                        CircleLayer secL = new CircleLayer(layerName + "_secure", "WifiDB_newest");
                        try { secL.setSourceLayer(layerId); } catch (Exception ignored) {}
                        secL.setProperties(PropertyFactory.circleColor(Color.parseColor(secureColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(1f), PropertyFactory.circleBlur(0.5f));
                        secL.setFilter(Expression.any(
                            Expression.eq(Expression.get("security"), Expression.literal("secure")),
                            Expression.eq(Expression.get("sectype"), Expression.literal(3)),
                            Expression.eq(Expression.get("sectype"), Expression.literal("3"))
                        ));
                        addServerLayerInOrder(secL, layerId);
                        Log.i("VectorMapActivity", "Added vector-backed WifiDB_newest layer for: " + layerId);
                        bringLiveLayersToTop();
                        return;
                    }
                }
            } catch (Exception ignored) {}

            if (layerId.equalsIgnoreCase("dailys") || lower.contains("daily") || lower.contains("dailys")) {
                geojsonUrl = base + "/api/geojson.php?func=exp_daily";
            } else if (layerId.equalsIgnoreCase("latests") || lower.contains("latest") || lower.contains("latests")) {
                geojsonUrl = base + "/api/geojson.php?func=exp_latest_ap";
            } else if (lower.contains("newest")) {
                geojsonUrl = base + "/api/geojson.php?func=exp_latest_ap";
            } else {
                // fallback to daily export (recent APs)
                geojsonUrl = base + "/api/geojson.php?func=exp_daily";
            }

                Log.i("VectorMapActivity", "Adding GeoJsonSource " + srcId + " -> " + geojsonUrl);
                GeoJsonSource gsrc = new GeoJsonSource(srcId, geojsonUrl);
                loadedStyle.addSource(gsrc);
                Toast.makeText(this, "Added GeoJson source: " + srcId, Toast.LENGTH_SHORT).show();

                // Try fetching the GeoJSON ourselves and apply it to the created source as a fallback
                fetchAndApplyGeoJson(srcId, geojsonUrl);

                // choose colors/radius based on layerId heuristics (map to open/wep/secure)
                String lidLower = layerId.toLowerCase(Locale.US);
                String openColor = "#1aff66";
                String wepColor = "#ffad33";
                String secureColor = "#ff1a1a";
                float radius = 2.0f;
                if (lidLower.contains("legacy")) { openColor = "#00802b"; wepColor = "#cc7a00"; secureColor = "#b30000"; radius = 3f; }
                else if (lidLower.contains("2to3")) { openColor = "#00b33c"; wepColor = "#e68a00"; secureColor = "#cc0000"; radius = 2.75f; }
                else if (lidLower.contains("1to2")) { openColor = "#00e64d"; wepColor = "#ff9900"; secureColor = "#e60000"; radius = 2.5f; }
                else if (lidLower.contains("0to1") || lidLower.contains("monthly") || lidLower.contains("weekly") || lidLower.contains("newest") || lidLower.contains("latest") || lidLower.contains("daily") ) { openColor = "#1aff66"; wepColor = "#ffad33"; secureColor = "#ff1a1a"; radius = 2f; }

                // create three filtered layers for open, wep, secure
                CircleLayer openLay = new CircleLayer(layerName + "_open", srcId);
                openLay.setProperties(PropertyFactory.circleColor(Color.parseColor(openColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(0.9f));
                        openLay.setFilter(Expression.any(
                                    Expression.eq(Expression.get("security"), Expression.literal("open")),
                                    Expression.eq(Expression.get("sectype"), Expression.literal(1)),
                                    Expression.eq(Expression.get("sectype"), Expression.literal("1"))
                                ));
                addServerLayerInOrder(openLay, layerId);

                CircleLayer wepLay = new CircleLayer(layerName + "_wep", srcId);
                wepLay.setProperties(PropertyFactory.circleColor(Color.parseColor(wepColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(0.9f));
                        wepLay.setFilter(Expression.any(
                                    Expression.eq(Expression.get("security"), Expression.literal("wep")),
                                    Expression.eq(Expression.get("sectype"), Expression.literal(2)),
                                    Expression.eq(Expression.get("sectype"), Expression.literal("2"))
                                ));
                addServerLayerInOrder(wepLay, layerId);

                CircleLayer secureLay = new CircleLayer(layerName + "_secure", srcId);
                secureLay.setProperties(PropertyFactory.circleColor(Color.parseColor(secureColor)), PropertyFactory.circleRadius(radius), PropertyFactory.circleOpacity(0.9f));
                        secureLay.setFilter(Expression.any(
                                    Expression.eq(Expression.get("security"), Expression.literal("secure")),
                                    Expression.eq(Expression.get("sectype"), Expression.literal(3)),
                                    Expression.eq(Expression.get("sectype"), Expression.literal("3"))
                                ));
                addServerLayerInOrder(secureLay, layerId);
                bringLiveLayersToTop();
        } catch (Exception ex) {
            // ignore failures to create server layer; callers will notify
        }
    }

    /**
     * Update the wifi live source with a GeoJSON string (FeatureCollection JSON).
     */
    public void setLiveWifiGeoJson(String featureCollectionJson) {
        if (wifiSource == null) return;
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            wifiSource.setGeoJson(fc);
        } catch (Exception ex) {
            // ignore
        }
    }

    public void setLiveBtGeoJson(String featureCollectionJson) {
        if (btSource == null) return;
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            btSource.setGeoJson(fc);
        } catch (Exception ex) {
        }
    }

    public void setLiveCellGeoJson(String featureCollectionJson) {
        if (cellSource == null) return;
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            cellSource.setGeoJson(fc);
        } catch (Exception ex) {
        }
    }

    /**
     * Helper: build a FeatureCollection JSON from simple device entries.
     * Each entry: double[]{lat, lon, signal} and properties are added.
     */
    public static String buildFeatureCollectionJsonFromDevices(java.util.List<double[]> devices, String typeLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (double[] d : devices) {
            if (d == null || d.length < 2) continue;
            double lat = d[0];
            double lon = d[1];
            String signal = d.length >= 3 ? Double.toString(d[2]) : "";
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            sb.append(lon).append(',').append(lat).append(" ]},\"properties\":{\"type\":\"").append(typeLabel).append("\",\"signal\":\"").append(signal).append("\"}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Refresh the layer toggle button labels based on whether layers/sources are present in the loaded style.
     * Buttons show `Hide X` when present and `Show X` when absent. Uses each button's tag to determine target.
     */
    private void refreshLayerButtonLabels() {
        if (layerButtonBar == null || loadedStyle == null) return;
        try {
            View inner = null;
            if (layerButtonBar instanceof ViewGroup && ((ViewGroup) layerButtonBar).getChildCount() > 0) {
                inner = ((ViewGroup) layerButtonBar).getChildAt(0);
            }
            if (!(inner instanceof LinearLayout)) return;
            LinearLayout ll = (LinearLayout) inner;

            Map<String,String> display = new HashMap<>();
            display.put("dailys","Day");
            display.put("WifiDB_weekly","Week");
            display.put("WifiDB_monthly","Month");
            display.put("WifiDB_0to1year","Year");
            display.put("WifiDB_1to2year","1-2 year");
            display.put("WifiDB_2to3year","2-3 year");
            display.put("WifiDB_Legacy","3+ year");
            display.put("cell_networks","Cell Networks");

            for (int i = 0; i < ll.getChildCount(); i++) {
                View v = ll.getChildAt(i);
                if (!(v instanceof Button)) continue;
                Button btn = (Button) v;
                Object tagObj = btn.getTag();
                if (tagObj == null) continue;
                String tag = tagObj.toString();
                String suf = display.containsKey(tag) ? display.get(tag) : btn.getText().toString();
                boolean present = layerPresentInStyle(tag);
                String newText = (present ? "Hide " : "Show ") + suf;
                btn.setText(newText);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Heuristic check whether a given logical layer id (tag) has any layer/source present in the loaded style.
     */
    private boolean layerPresentInStyle(String layerId) {
        if (loadedStyle == null) return false;
        try {
            // direct layer id
            if (loadedStyle.getLayer(layerId) != null) return true;
            // server-added layer id
            String srvLayer = "srv_" + layerId + "_layer";
            if (loadedStyle.getLayer(srvLayer) != null) return true;
            // any layer whose id contains the tag
            List<Layer> all = loadedStyle.getLayers();
            for (Layer L : all) {
                try {
                    String lid = L.getId();
                    if (lid != null && (lid.equalsIgnoreCase(layerId) || lid.contains(layerId) || lid.startsWith(layerId))) return true;
                } catch (Exception ignored) {}
            }
            // special-case cell networks backed by WifiDB_cells vector source
            if (layerId.toLowerCase(Locale.US).contains("cell") && loadedStyle.getSource("WifiDB_cells") != null) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void fetchAndApplyGeoJson(final String srcId, final String geojsonUrl) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(geojsonUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                int rc = conn.getResponseCode();
                if (rc != 200) {
                    final String msg = "GeoJSON fetch returned " + rc;
                    runOnUiThread(() -> Toast.makeText(VectorMapActivity.this, msg, Toast.LENGTH_SHORT).show());
                    return;
                }
                InputStream is = conn.getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                br.close();
                final String json = sb.toString();
                final FeatureCollection fc = FeatureCollection.fromJson(json);
                final int featCount = (fc == null || fc.features() == null) ? 0 : fc.features().size();
                Log.i("VectorMapActivity", "Fetched GeoJSON for " + srcId + ", features=" + featCount);
                runOnUiThread(() -> {
                    try {
                        if (loadedStyle != null && loadedStyle.getSource(srcId) instanceof GeoJsonSource) {
                            GeoJsonSource s = (GeoJsonSource) loadedStyle.getSource(srcId);
                            s.setGeoJson(fc);
                        }
                        Toast.makeText(VectorMapActivity.this, "Fetched " + featCount + " features for " + srcId, Toast.LENGTH_SHORT).show();
                        // log first feature props for debugging
                        try {
                            if (fc != null && fc.features() != null && !fc.features().isEmpty()) {
                                Feature f0 = fc.features().get(0);
                                if (f0 != null && f0.properties() != null) {
                                    Log.i("VectorMapActivity", "First feature props: " + f0.properties().toString());
                                }
                            }
                        } catch (Exception ignored) {}
                        // add an unfiltered debug layer so we can visually confirm points exist regardless of filters
                        try {
                            String dbgLayerId = srcId + "_all";
                            if (loadedStyle.getLayer(dbgLayerId) == null) {
                                CircleLayer dbg = new CircleLayer(dbgLayerId, srcId);
                                dbg.setProperties(PropertyFactory.circleColor(Color.parseColor("#FF00FF")), PropertyFactory.circleRadius(6f), PropertyFactory.circleOpacity(0.95f));
                                String logical = srcId;
                                if (logical != null && logical.startsWith("srv_")) logical = logical.substring(4);
                                addServerLayerInOrder(dbg, logical);
                                Toast.makeText(VectorMapActivity.this, "Added debug layer " + dbgLayerId, Toast.LENGTH_SHORT).show();
                                bringLiveLayersToTop();
                            }
                        } catch (Exception ex) {
                            Log.i("VectorMapActivity", "Failed to add debug layer: " + ex.getMessage());
                        }
                        refreshLayerButtonLabels();
                    } catch (Exception ex) {
                        Toast.makeText(VectorMapActivity.this, "Failed to apply GeoJSON: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ex) {
                final String msg = "Error fetching GeoJSON: " + ex.getClass().getSimpleName();
                runOnUiThread(() -> Toast.makeText(VectorMapActivity.this, msg, Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final Set<String> hiddenLayers = new HashSet<>();

    // Called by buttons in the layout; each button has its target layer id set as its tag.
    public void toggleLayerClick(View view) {
        if (loadedStyle == null) {
            Toast.makeText(this, "Style not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Object tag = view.getTag();
        if (tag == null) return;
        String layerId = tag.toString();
        Log.i("VectorMapActivity", "toggleLayerClick invoked for: " + layerId);
        try {
            Layer layer = loadedStyle.getLayer(layerId);
            Button btn = null;
            try { btn = (Button)view; } catch (Exception ignored) {}

            if (layer != null) {
                // direct layer id toggle
                if (hiddenLayers.contains(layerId)) {
                    layer.setProperties(PropertyFactory.visibility("visible"));
                    hiddenLayers.remove(layerId);
                    if (btn != null) btn.setText(btn.getText().toString().replaceFirst("Show","Hide"));
                } else {
                    layer.setProperties(PropertyFactory.visibility("none"));
                    hiddenLayers.add(layerId);
                    if (btn != null) btn.setText(btn.getText().toString().replaceFirst("Hide","Show"));
                }
            } else {
                // fallback: try to find layers that reference a source with this id or whose id matches/contains this tag
                List<Layer> allLayers = loadedStyle.getLayers();
                Set<String> matched = new HashSet<>();
                for (Layer L : allLayers) {
                    try {
                        String lid = L.getId();
                        if (lid != null && (lid.equalsIgnoreCase(layerId) || lid.startsWith(layerId) || lid.contains(layerId))) {
                            matched.add(lid);
                        }
                    } catch (Exception ignored) {}
                }
                if (matched.isEmpty()) {
                    // try to create a server-backed layer (show action) and look for it
                    try {
                        ensureServerGeoJsonLayer(layerId);
                        String srcId = "srv_" + layerId;
                        String srvLayerId = srcId + "_layer";
                        Layer srv = loadedStyle.getLayer(srvLayerId);
                        if (srv == null) {
                            // try to find any layer that references the source id or contains the layerId
                            for (Layer L : loadedStyle.getLayers()) {
                                try {
                                    String lid = L.getId();
                                    if (lid == null) continue;
                                    if (lid.equalsIgnoreCase(srvLayerId) || lid.contains(srcId) || lid.contains(layerId)) {
                                        srv = L;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        if (srv != null) {
                            try { srv.setProperties(PropertyFactory.visibility("visible")); } catch (Exception ignored) {}
                            hiddenLayers.remove(srv.getId());
                            if (btn != null) btn.setText(btn.getText().toString().replaceFirst("Show","Hide"));
                            Toast.makeText(this, "Added layer: " + srv.getId(), Toast.LENGTH_SHORT).show();
                            // refresh labels to reflect new state
                            refreshLayerButtonLabels();
                            return;
                        }
                    } catch (Exception ex) {
                        // show a helpful message for debugging
                        Toast.makeText(this, "Failed to add server layer for: " + layerId, Toast.LENGTH_SHORT).show();
                    }
                    Toast.makeText(this, "Layer not present: " + layerId, Toast.LENGTH_SHORT).show();
                    return;
                }
                // decide: if any matched layer is currently hidden, we'll show them all; otherwise hide them all
                boolean anyHidden = false;
                for (String mid : matched) { if (hiddenLayers.contains(mid)) { anyHidden = true; break; } }
                for (String mid : matched) {
                    try {
                        Layer L = loadedStyle.getLayer(mid);
                        if (L == null) continue;
                        if (anyHidden) {
                            L.setProperties(PropertyFactory.visibility("visible"));
                            hiddenLayers.remove(mid);
                        } else {
                            L.setProperties(PropertyFactory.visibility("none"));
                            hiddenLayers.add(mid);
                        }
                    } catch (Exception ignored) {}
                }
                if (btn != null) {
                    if (anyHidden) btn.setText(btn.getText().toString().replaceFirst("Show","Hide"));
                    else btn.setText(btn.getText().toString().replaceFirst("Hide","Show"));
                }
                Toast.makeText(this, "Toggled " + matched.size() + " layer(s)", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            Toast.makeText(this, "Unable to toggle layer: " + layerId, Toast.LENGTH_SHORT).show();
        }
    }

    private void attemptEnableLocationComponent() {
        if (mapLibreMap == null || loadedStyle == null) return;

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }

        try {
            LocationComponent locationComponent = mapLibreMap.getLocationComponent();
            LocationComponentActivationOptions options = LocationComponentActivationOptions.builder(this, loadedStyle)
                    .useDefaultLocationEngine(true)
                    .build();
            locationComponent.activateLocationComponent(options);
            locationComponent.setLocationComponentEnabled(true);
            locationComponent.setCameraMode(CameraMode.TRACKING);
            locationComponent.setRenderMode(RenderMode.COMPASS);

            // center on last known location if available
            Location last = locationComponent.getLastKnownLocation();
            if (last != null) {
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(last.getLatitude(), last.getLongitude()), 15.0));
            }
            trackingEnabled = true;
            updateFabIcon();
            // Ensure location layer renders above server layers; re-assert a few times
            try { bringLiveLayersToTop(); } catch (Exception ignored) {}
            if (mapView != null) {
                mapView.postDelayed(() -> { try { bringLiveLayersToTop(); } catch (Exception ignored) {} }, 500);
                mapView.postDelayed(() -> { try { bringLiveLayersToTop(); } catch (Exception ignored) {} }, 1500);
            }
        } catch (Exception ex) {
            Toast.makeText(this, "Unable to enable location component", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleTracking() {
        if (mapLibreMap == null) return;
        LocationComponent lc = mapLibreMap.getLocationComponent();
        if (lc == null || !lc.isLocationComponentEnabled()) {
            attemptEnableLocationComponent();
            return;
        }

        if (trackingEnabled) {
            lc.setCameraMode(CameraMode.NONE);
            trackingEnabled = false;
        } else {
            lc.setCameraMode(CameraMode.TRACKING);
            Location last = lc.getLastKnownLocation();
            if (last != null) {
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(last.getLatitude(), last.getLongitude()), 16.0));
            }
            trackingEnabled = true;
        }
        updateFabIcon();
    }

    private void updateFabIcon() {
        if (fabLocate == null) return;
        if (trackingEnabled) {
            fabLocate.setImageResource(android.R.drawable.ic_menu_mylocation);
        } else {
            fabLocate.setImageResource(android.R.drawable.ic_menu_mylocation);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        // register for live updates
        LiveMapUpdater.setActiveActivity(this);
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        // unregister live updates
        LiveMapUpdater.clearActiveActivity();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                attemptEnableLocationComponent();
            } else {
                Toast.makeText(this, "Location permission required to show current location", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
