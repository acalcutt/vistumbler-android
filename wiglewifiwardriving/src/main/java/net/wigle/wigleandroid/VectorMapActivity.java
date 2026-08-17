package net.wigle.wigleandroid;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import net.wigle.wigleandroid.ui.ScreenChildActivity;
import net.wigle.wigleandroid.util.WifiDbHistoryLayers;
import net.wigle.wigleandroid.util.WifiDbTileSources;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.graphics.Color;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
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
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import android.widget.LinearLayout;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.ListFragment;
import android.widget.Button;
import android.view.View;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.attribution.Attribution;
import org.maplibre.android.attribution.AttributionParser;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.util.Log;
import java.lang.StringBuilder;
import android.os.Looper;

public class VectorMapActivity extends ScreenChildActivity {
    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private Style loadedStyle;
    private GeoJsonSource wifiSource;
    private GeoJsonSource btSource;
    private GeoJsonSource cellSource;
    private FloatingActionButton fabLocate;
    private FloatingActionButton fabBearing;
    private FloatingActionButton fabAttribution;
    private View layerButtonBar;
    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private int currentInsetTop = 0;
    private int currentInsetBottom = 0;
    private int currentInsetRight = 0;
    private int currentInsetLeft = 0;
    private int fabOriginalBottomMargin = 0;
    private int fabOriginalRightMargin = 0;
    private int fabBearingOriginalBottomMargin = 0;
    private int fabBearingOriginalRightMargin = 0;
    private int fabAttributionOriginalBottomMargin = 0;
    private int fabAttributionOriginalLeftMargin = 0;
    private int layerBarOriginalTopMargin = 0;
    private static final String LIVE_DEBUG_TOKEN = "WIGLE_LIVE_DBG";
    private static final String LIVE_WIFI_OPEN_LAYER_ID = "live_wifi_open_layer";
    private static final String LIVE_WIFI_WEP_LAYER_ID = "live_wifi_wep_layer";
    private static final String LIVE_WIFI_SECURE_LAYER_ID = "live_wifi_secure_layer";
    private static final String LIVE_BT_LAYER_ID = "live_bt_layer";
    private static final String LIVE_CELL_LAYER_ID = "live_cell_layer";

    // Location tracking mode: OFF (no location), SHOW (show but don't follow), TRACK (follow location)
    private enum TrackingMode { OFF, SHOW, TRACK }
    private TrackingMode currentTrackingMode = TrackingMode.OFF;

    // Bearing mode: NORTH (always north up), COMPASS (rotate with compass), GPS (rotate with GPS bearing)
    private enum BearingMode { NORTH, COMPASS, GPS }
    private BearingMode currentBearingMode = BearingMode.NORTH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize MapLibre
        MapLibre.getInstance(this);

        // History overlays come from whichever WifiDB the user has configured.
        applyWifiDbUrlSetting();

        // Find out whether WifiDB's TileJSON endpoint can be reached, so the history
        // buttons fall back to the published archives when it cannot. One request
        // answers for every bucket; layers are added synchronously and do not wait on it.
        WifiDbTileSources.probeAsync();

        setContentView(R.layout.activity_vector_map);

        mapView = findViewById(R.id.mapView);
        fabLocate = findViewById(R.id.btn_locate);
        fabBearing = findViewById(R.id.btn_bearing);
        fabAttribution = findViewById(R.id.btn_attribution);
        layerButtonBar = findViewById(R.id.layer_button_bar);

        // Set up custom Toolbar as ActionBar
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.vector_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false); // Remove "Vector Map" label
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        // Dynamically set navigation icon with top inset for status bar

        Runnable setNavIconInset = () -> {

            int statusBarHeight = 0;
            int resource = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resource > 0) {
                statusBarHeight = getResources().getDimensionPixelSize(resource);
            }
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                android.graphics.drawable.Drawable navDrawable = new android.graphics.drawable.InsetDrawable(
                    androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_menu_revert),
                    0, statusBarHeight + 5, 0, 0);
                toolbar.setNavigationIcon(navDrawable);
            } else {
                if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && currentInsetRight > 0) {
                android.graphics.drawable.Drawable navDrawable = new android.graphics.drawable.InsetDrawable(
                    androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_menu_revert),
                    0, statusBarHeight + 5, currentInsetRight, 0);
                toolbar.setNavigationIcon(navDrawable);
                } else {
                android.graphics.drawable.Drawable navDrawable = new android.graphics.drawable.InsetDrawable(
                    androidx.core.content.ContextCompat.getDrawable(this, android.R.drawable.ic_menu_revert),
                    currentInsetLeft ,statusBarHeight + 5, 0, 0);
                toolbar.setNavigationIcon(navDrawable);
                }
            }
        };
        toolbar.post(setNavIconInset);

        // Listen for configuration changes to update the navigation icon inset and reset Toolbar height
        final android.content.res.Configuration[] lastConfig = {getResources().getConfiguration()};
        toolbar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                      int oldLeft, int oldTop, int oldRight, int oldBottom) {
                android.content.res.Configuration newConfig = getResources().getConfiguration();
                if (newConfig.orientation != lastConfig[0].orientation) {
                    lastConfig[0] = newConfig;
                    toolbar.post(setNavIconInset);
                }
            }
        });
        // capture original top margin for layer bar so we can shift it below status bar
        ViewGroup.LayoutParams lbLp = layerButtonBar != null ? layerButtonBar.getLayoutParams() : null;
        if (lbLp instanceof MarginLayoutParams) {
            layerBarOriginalTopMargin = ((MarginLayoutParams) lbLp).topMargin;
        }

        // capture original bottom and right margin of FABs
        ViewGroup.LayoutParams lp = fabLocate.getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            fabOriginalBottomMargin = ((MarginLayoutParams) lp).bottomMargin;
            fabOriginalRightMargin = ((MarginLayoutParams) lp).rightMargin;
        }
        ViewGroup.LayoutParams lpBearing = fabBearing.getLayoutParams();
        if (lpBearing instanceof MarginLayoutParams) {
            fabBearingOriginalBottomMargin = ((MarginLayoutParams) lpBearing).bottomMargin;
            fabBearingOriginalRightMargin = ((MarginLayoutParams) lpBearing).rightMargin;
        }
        ViewGroup.LayoutParams lpAttr = fabAttribution.getLayoutParams();
        if (lpAttr instanceof MarginLayoutParams) {
            fabAttributionOriginalBottomMargin = ((MarginLayoutParams) lpAttr).bottomMargin;
            fabAttributionOriginalLeftMargin = ((MarginLayoutParams) lpAttr).leftMargin;
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
                    currentInsetRight = sys.right;
                    currentInsetLeft = sys.left;
                    // Adjust only the navigation icon (back button) for status bar
                    if (toolbar != null) {
                        toolbar.post(() -> {
                            for (int i = 0; i < toolbar.getChildCount(); i++) {
                                View child = toolbar.getChildAt(i);
                                // Try to find the navigation icon by content description or class name
                                CharSequence desc = child.getContentDescription();
                                boolean isNavIcon = false;
                                if (desc != null && desc.toString().toLowerCase().contains("navigate")) {
                                    isNavIcon = true;
                                } else if (child.getClass().getName().toLowerCase().contains("navigation")) {
                                    isNavIcon = true;
                                }
                                // Currently no per-child margin adjustments are needed; navigation icon inset handled via InsetDrawable
                            }
                        });
                    }
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

                    // Decor padding adjustments intentionally omitted; inset handling occurs via margins and InsetDrawable
                    // adjust FAB margin to sit above nav bar (bottom) and away from side nav bar (right)
                    try {
                        ViewGroup.LayoutParams lp = fabLocate.getLayoutParams();
                        if (lp instanceof MarginLayoutParams) {
                            MarginLayoutParams mlp = (MarginLayoutParams) lp;
                            mlp.bottomMargin = fabOriginalBottomMargin + currentInsetBottom;
                            mlp.rightMargin = fabOriginalRightMargin + currentInsetRight;
                            fabLocate.setLayoutParams(mlp);
                        }
                    } catch (Exception ignored) {}
                    // adjust bearing FAB margin
                    try {
                        ViewGroup.LayoutParams lpB = fabBearing.getLayoutParams();
                        if (lpB instanceof MarginLayoutParams) {
                            MarginLayoutParams mlpB = (MarginLayoutParams) lpB;
                            mlpB.bottomMargin = fabBearingOriginalBottomMargin + currentInsetBottom;
                            mlpB.rightMargin = fabBearingOriginalRightMargin + currentInsetRight;
                            fabBearing.setLayoutParams(mlpB);
                        }
                    } catch (Exception ignored) {}
                    // adjust attribution FAB margin (bottom-left corner)
                    try {
                        ViewGroup.LayoutParams lpA = fabAttribution.getLayoutParams();
                        if (lpA instanceof MarginLayoutParams) {
                            MarginLayoutParams mlpA = (MarginLayoutParams) lpA;
                            mlpA.bottomMargin = fabAttributionOriginalBottomMargin + currentInsetBottom;
                            mlpA.leftMargin = fabAttributionOriginalLeftMargin + currentInsetLeft;
                            fabAttribution.setLayoutParams(mlpA);
                        }
                    } catch (Exception ignored) {}
                    // adjust MapView margins so map doesn't render behind status/nav bars
                    try {
                        if (mapView != null) {
                            Insets navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                            int statusBarHeight = 0;
                            int resource = getResources().getIdentifier("status_bar_height", "dimen", "android");
                            if (resource > 0) {
                                statusBarHeight = getResources().getDimensionPixelSize(resource);
                            }
                            ViewGroup.LayoutParams lpMap = mapView.getLayoutParams();
                            if (lpMap instanceof MarginLayoutParams) {
                                MarginLayoutParams mlpMap = (MarginLayoutParams) lpMap;
                                int orientation = getResources().getConfiguration().orientation;
                                if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                    // Always apply status bar height to top
                                    mlpMap.topMargin = statusBarHeight;
                                    mlpMap.bottomMargin = 0;
                                    // Menu/nav bar on right
                                    if (navBars.right > 0) {
                                        mlpMap.leftMargin = 0;
                                        mlpMap.rightMargin = navBars.right;
                                    }
                                    // Menu/nav bar on left
                                    else if (navBars.left > 0) {
                                        mlpMap.leftMargin = navBars.left;
                                        mlpMap.rightMargin = 0;
                                    } else {
                                        mlpMap.leftMargin = 0;
                                        mlpMap.rightMargin = 0;
                                    }
                                } else {
                                    // Portrait: status bar on top, nav bar on bottom, left/right = nav bar insets
                                    mlpMap.topMargin = statusBarHeight;
                                    mlpMap.bottomMargin = navBars.bottom;
                                    mlpMap.leftMargin = navBars.left;
                                    mlpMap.rightMargin = navBars.right;
                                }
                                mapView.setLayoutParams(mlpMap);
                            }
                        }
                    } catch (Exception ignored) {}
                    // NOTE: MapView renders edge-to-edge; FABs and layer bar handle their own insets
                    // update map padding if map is ready — only for layer bar overlay (margins handle system bars)
                    try {
                        if (mapLibreMap != null) {
                            if (layerButtonBar != null) {
                                layerButtonBar.post(() -> {
                                    int layerBarHeight = layerButtonBar.getHeight();
                                    try { mapLibreMap.setPadding(0, layerBarHeight, 0, 0); } catch (Exception ignored) {}
                                });
                            } else {
                                try { mapLibreMap.setPadding(0, 0, 0, 0); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                    // consume insets so system doesn't apply them automatically
                    return WindowInsetsCompat.CONSUMED;
                }
            });
        }
        fabLocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleTrackingMode();
            }
        });
        fabBearing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleBearingMode();
            }
        });
        fabAttribution.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAttributionDialog();
            }
        });
        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(@NonNull MapLibreMap mapLibreMap) {
                VectorMapActivity.this.mapLibreMap = mapLibreMap;
                
                // Disable built-in attribution and logo - we have our own attribution button
                mapLibreMap.getUiSettings().setAttributionEnabled(false);
                mapLibreMap.getUiSettings().setLogoEnabled(false);
                
                try {
                    final String styleUrl = "https://tiles.wifidb.net/styles/WDB_COLOR_RELIEF/style.json";
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
                            // add live GeoJSON sources - layers will be created by ensureLiveLayersExist
                            try {
                                // wifi source
                                wifiSource = new GeoJsonSource("live_wifi", FeatureCollection.fromFeatures(new Feature[]{}));
                                loadedStyle.addSource(wifiSource);

                                // bluetooth source
                                btSource = new GeoJsonSource("live_bt", FeatureCollection.fromFeatures(new Feature[]{}));
                                loadedStyle.addSource(btSource);

                                // cell source
                                cellSource = new GeoJsonSource("live_cell", FeatureCollection.fromFeatures(new Feature[]{}));
                                loadedStyle.addSource(cellSource);
                                
                                // Create initial layers using the helper function
                                ensureLiveLayersExist();
                            } catch (Exception ex) {
                                Log.w("VectorMapActivity", "Failed to create live sources/layers: " + ex.getMessage());
                            }
                            // apply map padding for layer bar overlay (margins handle system bars)
                            try {
                                int layerBarHeight = (layerButtonBar != null) ? layerButtonBar.getHeight() : 0;
                                if (layerBarHeight > 0) {
                                    mapLibreMap.setPadding(0, layerBarHeight, 0, 0);
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
                                                    LIVE_WIFI_OPEN_LAYER_ID, LIVE_WIFI_WEP_LAYER_ID, LIVE_WIFI_SECURE_LAYER_ID,
                                                    LIVE_BT_LAYER_ID, LIVE_CELL_LAYER_ID);
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
                                                        out.append("Latitude: ").append(net.getLatLng().getLatitude()).append('\n');
                                                        out.append("Longitude: ").append(net.getLatLng().getLongitude()).append('\n');
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
                            // re-register with LiveMapUpdater so it will push any cached live features now that style/sources are available
                            try { LiveMapUpdater.setActiveActivity(VectorMapActivity.this); } catch (Exception ignored) {}

                            // Check for a network to focus on
                            Intent intent = getIntent();
                            if (intent != null && intent.hasExtra("lat") && intent.hasExtra("lon")) {
                                double lat = intent.getDoubleExtra("lat", 0);
                                double lon = intent.getDoubleExtra("lon", 0);
                                if (lat != 0 && lon != 0) {
                                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                            new LatLng(lat, lon), 18));
                                }
                            } else {
                                // No specific location requested - center on user's current location
                                try {
                                    Location last = null;
                                    LocationComponent lc = mapLibreMap.getLocationComponent();
                                    if (lc != null && lc.isLocationComponentActivated()) {
                                        last = lc.getLastKnownLocation();
                                    }
                                    // Fallback to app's GPS listener location if available
                                    if (last == null && ListFragment.lameStatic.location != null) {
                                        last = ListFragment.lameStatic.location;
                                    }
                                    if (last != null) {
                                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                                new LatLng(last.getLatitude(), last.getLongitude()), 15));
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    });
                } catch (Exception ex) {
                    // log but avoid crashing during style load
                }
            }
        });
    }

    /**
     * Point the history overlays at the configured WifiDB, so a self-hosted install
     * serves its own archives. The setting holds a site root, which may or may not
     * already carry the /api suffix; normalized the same way WifiDBApiManager does it.
     */
    private void applyWifiDbUrlSetting() {
        try {
            final android.content.SharedPreferences prefs =
                    getSharedPreferences(net.wigle.wigleandroid.util.PreferenceKeys.SHARED_PREFS, 0);
            String base = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_URL, "");
            if (base == null || base.trim().isEmpty()) return;

            base = base.trim();
            if (base.endsWith("/api/")) {
                base = base.substring(0, base.length() - 5);
            } else if (base.endsWith("/api")) {
                base = base.substring(0, base.length() - 4);
            }
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            WifiDbTileSources.setApiBaseUrl(base + "/api");
        } catch (Exception ex) {
            Log.w("VectorMapActivity", "failed to read WifiDB URL setting: " + ex.getMessage());
        }
    }

    private void logStyleSummary() {
        try {
            if (loadedStyle == null) {
                Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": Style not loaded for summary");
                return;
            }
            // History buckets are runtime sources, added on demand by the toolbar buttons.
            StringBuilder buckets = new StringBuilder();
            for (String bucket : WifiDbHistoryLayers.BUCKET_ORDER) {
                if (WifiDbHistoryLayers.isAdded(loadedStyle, bucket)) {
                    if (buckets.length() > 0) buckets.append(',');
                    buckets.append(bucket);
                }
            }
            boolean hasLiveWifi = loadedStyle.getSource("live_wifi") != null;
            boolean hasLiveBt = loadedStyle.getSource("live_bt") != null;
            boolean hasLiveCell = loadedStyle.getSource("live_cell") != null;
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": Style summary: history buckets added - " + (buckets.length() == 0 ? "(none)" : buckets.toString()));
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": Live sources - live_wifi=" + hasLiveWifi + " live_bt=" + hasLiveBt + " live_cell=" + hasLiveCell);
            List<Layer> all = loadedStyle.getLayers();
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": Style has " + (all == null ? 0 : all.size()) + " layers. Listing up to 40 ids:");
            if (all != null) {
                int c = 0;
                for (Layer L : all) {
                    try {
                        String lid = L.getId();
                        boolean isLive = lid != null && (lid.startsWith("live_") || lid.contains("live_wifi") || lid.contains("live_bt") || lid.contains("live_cell"));
                        Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": layer[" + c + "] id=" + lid + (isLive ? " [LIVE]" : ""));
                    } catch (Exception ignored) {}
                    c++;
                    if (c > 40) break;
                }
            }
        } catch (Exception ex) {
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": Failed to log style summary: " + ex.getMessage());
        }
    }

    /**
     * Add a layer but prefer to insert it below the live layers so live points remain on top.
     */
    private void addLayerBelowLive(Layer layer) {
        try {
            if (loadedStyle == null || layer == null) return;
            // prefer to insert server layers below the main live wifi open layer
            String ref = LIVE_WIFI_OPEN_LAYER_ID;
            if (loadedStyle.getLayer(ref) != null) {
                try { loadedStyle.addLayerBelow(layer, ref); return; } catch (Exception ignored) {}
            }
            // fallback: add normally
            loadedStyle.addLayer(layer);
        } catch (Exception ignored) {}
    }

    /**
     * Ensure the live layers are on top. 
     * NOTE: Removing a layer invalidates its Java reference, so we must create fresh layer objects.
     */
    private void bringLiveLayersToTop() {
        if (loadedStyle == null) return;
        String[] liveIds = new String[]{LIVE_WIFI_OPEN_LAYER_ID, LIVE_WIFI_WEP_LAYER_ID, LIVE_WIFI_SECURE_LAYER_ID, LIVE_BT_LAYER_ID, LIVE_CELL_LAYER_ID};
        Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": bringLiveLayersToTop start");

        // Detect any dynamic location-like layer ids so we can position above them
        String anchor = null;
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
            if (!dynamicIds.isEmpty()) {
                anchor = dynamicIds.get(dynamicIds.size() - 1);
                Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": bringLiveLayersToTop: dynamicIds=" + dynamicIds + " anchor=" + anchor);
            }
        } catch (Exception ignored) {}

        // For each live layer, remove it and create a fresh layer object to re-add
        // (Once removeLayer is called, the old Layer reference becomes invalid in MapLibre)
        for (String id : liveIds) {
            try {
                if (loadedStyle.getLayer(id) != null) {
                    loadedStyle.removeLayer(id);
                }
                // Create fresh layer based on id
                CircleLayer fresh = createFreshLiveLayer(id);
                if (fresh == null) continue;
                if (anchor != null) {
                    try {
                        loadedStyle.addLayerAbove(fresh, anchor);
                    } catch (Exception ex) {
                        Log.w("VectorMapActivity", "addLayerAbove failed for " + id + ": " + ex.getMessage());
                        try { loadedStyle.addLayer(fresh); } catch (Exception ex2) { Log.w("VectorMapActivity", "fallback addLayer also failed for " + id + ": " + ex2.getMessage()); }
                    }
                } else {
                    loadedStyle.addLayer(fresh);
                }
            } catch (Exception ex) { Log.w("VectorMapActivity", "Exception in bringLiveLayersToTop for " + id + ": " + ex.getMessage()); }
        }

        Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": bringLiveLayersToTop end");
    }

    /**
     * Create a fresh CircleLayer for the given live layer ID.
     */
    private CircleLayer createFreshLiveLayer(String layerId) {
        try {
            if (LIVE_WIFI_OPEN_LAYER_ID.equals(layerId)) {
                CircleLayer layer = new CircleLayer(layerId, "live_wifi");
                layer.setProperties(
                    PropertyFactory.circleColor(Color.parseColor("#4CAF50")),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleOpacity(0.9f)
                );
                layer.setFilter(Expression.any(
                    Expression.eq(Expression.get("security"), Expression.literal("open")),
                    Expression.eq(Expression.get("sectype"), Expression.literal(1)),
                    Expression.eq(Expression.get("sectype"), Expression.literal("1"))
                ));
                return layer;
            } else if (LIVE_WIFI_WEP_LAYER_ID.equals(layerId)) {
                CircleLayer layer = new CircleLayer(layerId, "live_wifi");
                layer.setProperties(
                    PropertyFactory.circleColor(Color.parseColor("#FF9800")),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleOpacity(0.9f)
                );
                layer.setFilter(Expression.any(
                    Expression.eq(Expression.get("security"), Expression.literal("wep")),
                    Expression.eq(Expression.get("sectype"), Expression.literal(2)),
                    Expression.eq(Expression.get("sectype"), Expression.literal("2"))
                ));
                return layer;
            } else if (LIVE_WIFI_SECURE_LAYER_ID.equals(layerId)) {
                CircleLayer layer = new CircleLayer(layerId, "live_wifi");
                layer.setProperties(
                    PropertyFactory.circleColor(Color.parseColor("#F44336")),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleOpacity(0.9f)
                );
                layer.setFilter(Expression.any(
                    Expression.eq(Expression.get("security"), Expression.literal("secure")),
                    Expression.eq(Expression.get("sectype"), Expression.literal(3)),
                    Expression.eq(Expression.get("sectype"), Expression.literal("3"))
                ));
                return layer;
            } else if (LIVE_BT_LAYER_ID.equals(layerId)) {
                CircleLayer layer = new CircleLayer(layerId, "live_bt");
                layer.setProperties(
                    PropertyFactory.circleColor(Color.parseColor("#2196F3")),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleOpacity(0.9f)
                );
                return layer;
            } else if (LIVE_CELL_LAYER_ID.equals(layerId)) {
                CircleLayer layer = new CircleLayer(layerId, "live_cell");
                layer.setProperties(
                    PropertyFactory.circleColor(Color.parseColor("#9C27B0")),
                    PropertyFactory.circleRadius(4f),
                    PropertyFactory.circleOpacity(0.9f)
                );
                return layer;
            }
        } catch (Exception ex) {
            Log.w("VectorMapActivity", "createFreshLiveLayer failed for " + layerId + ": " + ex.getMessage());
        }
        return null;
    }

    /**
     * Dump the full style layer list to logcat for debugging layer ordering.
     */
    private void logFullStyleLayers() {
        if (loadedStyle == null) return;
        try {
            List<Layer> layers = loadedStyle.getLayers();
            if (layers == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("Style full layer list (count=").append(layers.size()).append("):");
            for (Layer L : layers) {
                try { sb.append("\n  ").append(L.getId()); } catch (Exception ignored) {}
            }
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": " + sb.toString());
        } catch (Exception ex) {
            Log.w("VectorMapActivity", "logFullStyleLayers failed: " + ex.getMessage());
        }
    }

    /**
     * Ensure expected live circle layers exist (wifi open/wep/secure, bt, cell).
     * Safe to call multiple times. Uses createFreshLiveLayer to avoid code duplication.
     */
    private void ensureLiveLayersExist() {
        // ensure this runs on the main/UI thread because MapLibre style modifications must be made there
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                runOnUiThread(() -> ensureLiveLayersExist());
            } catch (Exception ex) {
                Log.w("VectorMapActivity", LIVE_DEBUG_TOKEN + ": ensureLiveLayersExist could not post to UI thread: " + ex.getMessage());
            }
            return;
        }
        if (loadedStyle == null) return;
        
        String[] liveIds = new String[]{LIVE_WIFI_OPEN_LAYER_ID, LIVE_WIFI_WEP_LAYER_ID, LIVE_WIFI_SECURE_LAYER_ID, LIVE_BT_LAYER_ID, LIVE_CELL_LAYER_ID};
        
        try {
            for (String layerId : liveIds) {
                if (loadedStyle.getLayer(layerId) == null) {
                    try {
                        CircleLayer fresh = createFreshLiveLayer(layerId);
                        if (fresh != null) {
                            loadedStyle.addLayer(fresh);
                            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": ensureLiveLayersExist: created layer " + layerId);
                            // Verify layer was actually added
                            if (loadedStyle.getLayer(layerId) == null) {
                                Log.w("VectorMapActivity", LIVE_DEBUG_TOKEN + ": WARNING: layer " + layerId + " was not retained after addLayer!");
                            }
                        }
                    } catch (Exception ex) { 
                        Log.w("VectorMapActivity", LIVE_DEBUG_TOKEN + ": failed add " + layerId + ": " + ex.getMessage()); 
                    }
                }
            }
            // Note: We don't call bringLiveLayersToTop here anymore to avoid destroying layers we just created.
            // Layers will be moved to top on next location update or explicit call.
            try { logFullStyleLayers(); } catch (Exception ignored) {}
        } catch (Exception ex) {
            Log.w("VectorMapActivity", LIVE_DEBUG_TOKEN + ": ensureLiveLayersExist failed: " + ex.getMessage());
        }
    }

    /**
     * Update the wifi live source with a GeoJSON string (FeatureCollection JSON).
     */
    public void setLiveWifiGeoJson(String featureCollectionJson) {
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            int count = fc == null || fc.features() == null ? 0 : fc.features().size();
            Log.i("VectorMapActivity", "setLiveWifiGeoJson: features=" + count);
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": setLiveWifi features=" + count);
            if (count > 0) {
                try {
                    Feature f0 = fc.features().get(0);
                    if (f0 != null && f0.properties() != null) {
                        Log.i("VectorMapActivity", "live wifi first props: " + f0.properties().toString());
                    }
                } catch (Exception ignored) {}
            }
            // All MapLibre style/source/layer modifications must run on the UI thread
            if (loadedStyle != null) {
                final int fCount = count;
                runOnUiThread(() -> {
                    try {
                        if (wifiSource == null && loadedStyle.getSource("live_wifi") instanceof GeoJsonSource) {
                            wifiSource = (GeoJsonSource) loadedStyle.getSource("live_wifi");
                        }
                        if (wifiSource != null) {
                            try { wifiSource.setGeoJson(fc); } catch (Exception ex) { Log.w("VectorMapActivity", "wifiSource.setGeoJson failed: " + ex.getMessage()); }
                        } else {
                            try {
                                Log.i("VectorMapActivity", "wifiSource null; creating live_wifi source");
                                GeoJsonSource s = new GeoJsonSource("live_wifi", fc);
                                loadedStyle.addSource(s);
                                wifiSource = s;
                            } catch (Exception ex) { Log.w("VectorMapActivity", "Failed to add live_wifi source: " + ex.getMessage()); }
                        }
                        // Ensure layers exist
                        try { ensureLiveLayersExist(); } catch (Exception ignored) {}
                        // make sure our live layers are visible
                        try {
                            String[] wifiLayerIds = {LIVE_WIFI_OPEN_LAYER_ID, LIVE_WIFI_WEP_LAYER_ID, LIVE_WIFI_SECURE_LAYER_ID};
                            for (String layerId : wifiLayerIds) {
                                Layer l = loadedStyle == null ? null : loadedStyle.getLayer(layerId);
                                if (l != null) l.setProperties(PropertyFactory.visibility("visible"));
                            }
                        } catch (Exception ignored) {}
                        // debug layer disabled to avoid occluding live colored circles
                        try {
                            if (loadedStyle != null && loadedStyle.getLayer("live_wifi_all") != null) {
                                try { loadedStyle.getLayer("live_wifi_all").setProperties(PropertyFactory.visibility("none")); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                        try { logFullStyleLayers(); } catch (Exception ignored) {}
                    } catch (Exception ex) { Log.w("VectorMapActivity", "UI-thread update failed in setLiveWifiGeoJson: " + ex.getMessage()); }
                });
            }
        } catch (Exception ex) {
            Log.i("VectorMapActivity", "Failed setLiveWifiGeoJson: " + ex.getMessage());
        }
    }

    public void setLiveBtGeoJson(String featureCollectionJson) {
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            int count = fc == null || fc.features() == null ? 0 : fc.features().size();
            Log.i("VectorMapActivity", "setLiveBtGeoJson: features=" + count);
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": setLiveBt features=" + count);
            if (loadedStyle != null) {
                runOnUiThread(() -> {
                    try {
                        if (btSource == null && loadedStyle.getSource("live_bt") instanceof GeoJsonSource) {
                            btSource = (GeoJsonSource) loadedStyle.getSource("live_bt");
                        }
                        if (btSource != null) {
                            try { btSource.setGeoJson(fc); } catch (Exception ex) { Log.w("VectorMapActivity", "btSource.setGeoJson failed: " + ex.getMessage()); }
                        } else {
                            try { GeoJsonSource s = new GeoJsonSource("live_bt", fc); loadedStyle.addSource(s); btSource = s; } catch (Exception ex) { Log.w("VectorMapActivity", "Failed to add live_bt source: " + ex.getMessage()); }
                        }
                        try { ensureLiveLayersExist(); } catch (Exception ignored) {}
                        try { Layer l = loadedStyle == null ? null : loadedStyle.getLayer(LIVE_BT_LAYER_ID); if (l != null) l.setProperties(PropertyFactory.visibility("visible")); } catch (Exception ignored) {}
                    } catch (Exception ex) { Log.w("VectorMapActivity", "UI-thread update failed in setLiveBtGeoJson: " + ex.getMessage()); }
                });
            }
        } catch (Exception ex) { Log.i("VectorMapActivity", "Failed setLiveBtGeoJson: " + ex.getMessage()); }
    }

    public void setLiveCellGeoJson(String featureCollectionJson) {
        try {
            FeatureCollection fc = FeatureCollection.fromJson(featureCollectionJson);
            int count = fc == null || fc.features() == null ? 0 : fc.features().size();
            Log.i("VectorMapActivity", "setLiveCellGeoJson: features=" + count);
            Log.i("VectorMapActivity", LIVE_DEBUG_TOKEN + ": setLiveCell features=" + count);
            if (loadedStyle != null) {
                runOnUiThread(() -> {
                    try {
                        if (cellSource == null && loadedStyle.getSource("live_cell") instanceof GeoJsonSource) {
                            cellSource = (GeoJsonSource) loadedStyle.getSource("live_cell");
                        }
                        if (cellSource != null) {
                            try { cellSource.setGeoJson(fc); } catch (Exception ex) { Log.w("VectorMapActivity", "cellSource.setGeoJson failed: " + ex.getMessage()); }
                        } else {
                            try { GeoJsonSource s = new GeoJsonSource("live_cell", fc); loadedStyle.addSource(s); cellSource = s; } catch (Exception ex) { Log.w("VectorMapActivity", "Failed to add live_cell source: " + ex.getMessage()); }
                        }
                        try { ensureLiveLayersExist(); } catch (Exception ignored) {}
                        try { Layer l = loadedStyle == null ? null : loadedStyle.getLayer(LIVE_CELL_LAYER_ID); if (l != null) l.setProperties(PropertyFactory.visibility("visible")); } catch (Exception ignored) {}
                    } catch (Exception ex) { Log.w("VectorMapActivity", "UI-thread update failed in setLiveCellGeoJson: " + ex.getMessage()); }
                });
            }
        } catch (Exception ex) { Log.i("VectorMapActivity", "Failed setLiveCellGeoJson: " + ex.getMessage()); }
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
     * Refresh the history button labels. Each button's tag is its bucket; a button reads
     * `Hide X` when that bucket's layers are in the style and visible, `Show X` otherwise.
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

            for (int i = 0; i < ll.getChildCount(); i++) {
                View v = ll.getChildAt(i);
                if (!(v instanceof Button)) continue;
                Button btn = (Button) v;
                Object tagObj = btn.getTag();
                if (tagObj == null) continue;
                String bucket = tagObj.toString();
                if (!WifiDbHistoryLayers.isKnownBucket(bucket)) continue;
                boolean shown = WifiDbHistoryLayers.isAdded(loadedStyle, bucket)
                        && !hiddenLayers.contains(WifiDbHistoryLayers.layerIdsFor(bucket)[0]);
                btn.setText((shown ? "Hide " : "Show ") + WifiDbHistoryLayers.labelFor(bucket));
            }
        } catch (Exception ignored) {}
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final Set<String> hiddenLayers = new HashSet<>();

    /**
     * Called by the history buttons in the toolbar; each button's tag is its WifiDB
     * bucket name. The first press adds that bucket's vector source and layers, later
     * presses only flip visibility so the tiles already fetched are kept.
     */
    public void toggleLayerClick(View view) {
        if (loadedStyle == null) {
            Toast.makeText(this, "Style not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Object tag = view.getTag();
        if (tag == null) return;
        String bucket = tag.toString();
        Log.i("VectorMapActivity", "toggleLayerClick invoked for bucket: " + bucket);

        Button btn = (view instanceof Button) ? (Button) view : null;
        String label = WifiDbHistoryLayers.labelFor(bucket);

        if (!WifiDbHistoryLayers.isKnownBucket(bucket)) {
            Toast.makeText(this, "Unknown layer: " + bucket, Toast.LENGTH_SHORT).show();
            Log.w("VectorMapActivity", "toggleLayerClick called with unknown bucket: " + bucket);
            return;
        }

        try {
            String[] layerIds = WifiDbHistoryLayers.layerIdsFor(bucket);

            if (!WifiDbHistoryLayers.isAdded(loadedStyle, bucket)) {
                // Layers are added below the live-scan layer so local points stay on top.
                if (WifiDbHistoryLayers.add(loadedStyle, bucket, LIVE_WIFI_OPEN_LAYER_ID)) {
                    for (String id : layerIds) hiddenLayers.remove(id);
                    Toast.makeText(this, "Showing " + label, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to add " + label, Toast.LENGTH_SHORT).show();
                }
            } else {
                boolean wasHidden = hiddenLayers.contains(layerIds[0]);
                String visibility = wasHidden ? "visible" : "none";
                for (String id : layerIds) {
                    Layer layer = loadedStyle.getLayer(id);
                    if (layer != null) layer.setProperties(PropertyFactory.visibility(visibility));
                    if (wasHidden) {
                        hiddenLayers.remove(id);
                    } else {
                        hiddenLayers.add(id);
                    }
                }
                Toast.makeText(this, (wasHidden ? "Showing " : "Hiding ") + label, Toast.LENGTH_SHORT).show();
            }

            if (btn != null) refreshLayerButtonLabels();
        } catch (Exception ex) {
            Toast.makeText(this, "Unable to toggle layer: " + label, Toast.LENGTH_SHORT).show();
            Log.w("VectorMapActivity", "toggleLayerClick exception: " + ex.getMessage(), ex);
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
            if (!locationComponent.isLocationComponentActivated()) {
                LocationComponentActivationOptions options = LocationComponentActivationOptions.builder(this, loadedStyle)
                        .useDefaultLocationEngine(true)
                        .build();
                locationComponent.activateLocationComponent(options);
                
                // Add listener to detect when user gesture dismisses tracking
                locationComponent.addOnCameraTrackingChangedListener(new OnCameraTrackingChangedListener() {
                    @Override
                    public void onCameraTrackingDismissed() {
                        // User panned/scrolled the map while in tracking mode
                        // Switch from TRACK to SHOW mode so they can easily re-enable tracking
                        if (currentTrackingMode == TrackingMode.TRACK) {
                            currentTrackingMode = TrackingMode.SHOW;
                            VectorMapActivity.this.runOnUiThread(() -> {
                                updateFabIcons();
                                Toast.makeText(VectorMapActivity.this, "Location: Show position", Toast.LENGTH_SHORT).show();
                            });
                        }
                    }

                    @Override
                    public void onCameraTrackingChanged(int currentMode) {
                        // Optional: could log or react to mode changes
                    }
                });
            }
            locationComponent.setLocationComponentEnabled(true);
            
            // Apply the appropriate camera mode based on current tracking and bearing modes
            applyCameraAndRenderMode();

            // center on last known location if available and tracking
            if (currentTrackingMode == TrackingMode.TRACK) {
                Location last = locationComponent.getLastKnownLocation();
                if (last != null) {
                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(last.getLatitude(), last.getLongitude()), 15.0));
                }
            }
            
            updateFabIcons();
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

    /**
     * Compute and apply the appropriate CameraMode and RenderMode based on current tracking and bearing modes.
     * 
     * CameraMode options:
     * - NONE (8): No camera tracking
     * - NONE_COMPASS (16): No tracking, but tracks compass bearing
     * - NONE_GPS (22): No tracking, but tracks GPS bearing
     * - TRACKING (24): Camera tracks location
     * - TRACKING_COMPASS (32): Tracks location with compass bearing
     * - TRACKING_GPS (34): Tracks location with GPS bearing
     * - TRACKING_GPS_NORTH (36): Tracks location, bearing always north
     */
    private void applyCameraAndRenderMode() {
        if (mapLibreMap == null) return;
        LocationComponent lc = mapLibreMap.getLocationComponent();
        if (lc == null) return;

        int cameraMode;
        int renderMode;

        switch (currentTrackingMode) {
            case TRACK:
                // Camera follows location
                switch (currentBearingMode) {
                    case COMPASS:
                        cameraMode = CameraMode.TRACKING_COMPASS;
                        renderMode = RenderMode.COMPASS;
                        break;
                    case GPS:
                        cameraMode = CameraMode.TRACKING_GPS;
                        renderMode = RenderMode.GPS;
                        break;
                    case NORTH:
                    default:
                        cameraMode = CameraMode.TRACKING_GPS_NORTH;
                        renderMode = RenderMode.COMPASS;
                        break;
                }
                break;
            case SHOW:
                // Location shown but camera doesn't follow
                switch (currentBearingMode) {
                    case COMPASS:
                        cameraMode = CameraMode.NONE_COMPASS;
                        renderMode = RenderMode.COMPASS;
                        break;
                    case GPS:
                        cameraMode = CameraMode.NONE_GPS;
                        renderMode = RenderMode.GPS;
                        break;
                    case NORTH:
                    default:
                        cameraMode = CameraMode.NONE;
                        renderMode = RenderMode.COMPASS;
                        break;
                }
                break;
            case OFF:
            default:
                cameraMode = CameraMode.NONE;
                renderMode = RenderMode.NORMAL;
                break;
        }

        try {
            lc.setCameraMode(cameraMode);
            lc.setRenderMode(renderMode);
        } catch (Exception ex) {
            Log.w("VectorMapActivity", "Failed to set camera/render mode: " + ex.getMessage());
        }
    }

    /**
     * Cycle through tracking modes: OFF -> SHOW -> TRACK -> OFF
     */
    private void cycleTrackingMode() {
        if (mapLibreMap == null) return;
        LocationComponent lc = mapLibreMap.getLocationComponent();
        
        // If location component not enabled, enable it first and set to SHOW mode
        if (lc == null || !lc.isLocationComponentEnabled()) {
            currentTrackingMode = TrackingMode.SHOW;
            attemptEnableLocationComponent();
            showTrackingModeToast();
            return;
        }

        // Cycle through modes
        switch (currentTrackingMode) {
            case OFF:
                currentTrackingMode = TrackingMode.SHOW;
                lc.setLocationComponentEnabled(true);
                break;
            case SHOW:
                currentTrackingMode = TrackingMode.TRACK;
                // Animate to current location when entering tracking mode
                Location last = lc.getLastKnownLocation();
                if (last != null) {
                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(last.getLatitude(), last.getLongitude()), 16.0));
                }
                break;
            case TRACK:
                currentTrackingMode = TrackingMode.OFF;
                lc.setLocationComponentEnabled(false);
                break;
        }

        applyCameraAndRenderMode();
        updateFabIcons();
        showTrackingModeToast();
    }

    /**
     * Cycle through bearing modes: NORTH -> COMPASS -> GPS -> NORTH
     */
    private void cycleBearingMode() {
        switch (currentBearingMode) {
            case NORTH:
                currentBearingMode = BearingMode.COMPASS;
                break;
            case COMPASS:
                currentBearingMode = BearingMode.GPS;
                break;
            case GPS:
                currentBearingMode = BearingMode.NORTH;
                break;
        }

        applyCameraAndRenderMode();
        updateFabIcons();
        showBearingModeToast();
    }

    private void showTrackingModeToast() {
        String message;
        switch (currentTrackingMode) {
            case SHOW:
                message = "Location: Show position";
                break;
            case TRACK:
                message = "Location: Follow position";
                break;
            case OFF:
            default:
                message = "Location: Off";
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showBearingModeToast() {
        String message;
        switch (currentBearingMode) {
            case COMPASS:
                message = "Bearing: Compass";
                break;
            case GPS:
                message = "Bearing: GPS direction";
                break;
            case NORTH:
            default:
                message = "Bearing: North up";
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showAttributionDialog() {
        StringBuilder attributionText = new StringBuilder();
        
        // Add WifiDB attribution
        attributionText.append("Map Data Sources:\n\n");
        attributionText.append("• WifiDB - WiFi/Cell/Bluetooth network data\n");
        attributionText.append("  https://wifidb.net\n\n");
        
        // Parse attributions from the style
        if (loadedStyle != null) {
            try {
                // Extract attribution strings from sources
                java.util.List<String> attrStrings = new java.util.ArrayList<>();
                for (org.maplibre.android.style.sources.Source source : loadedStyle.getSources()) {
                    String attr = source.getAttribution();
                    if (attr != null && !attr.isEmpty()) {
                        attrStrings.add(attr);
                    }
                }
                Set<Attribution> attributions = new AttributionParser.Options(this)
                        .withAttributionData(attrStrings.toArray(new String[0]))
                        .build()
                        .getAttributions();
                
                if (attributions != null && !attributions.isEmpty()) {
                    for (Attribution attr : attributions) {
                        String title = attr.getTitle();
                        String url = attr.getUrl();
                        if (title != null && !title.isEmpty()) {
                            attributionText.append("• ").append(title);
                            if (url != null && !url.isEmpty()) {
                                attributionText.append("\n  ").append(url);
                            }
                            attributionText.append("\n\n");
                        }
                    }
                }
            } catch (Exception ex) {
                Log.w("VectorMapActivity", "Failed to parse attributions: " + ex.getMessage());
            }
        }
        
        // Add MapLibre attribution
        attributionText.append("• MapLibre GL\n");
        attributionText.append("  https://maplibre.org\n\n");
        
        // Add OpenStreetMap attribution (common source for vector tiles)
        attributionText.append("• © OpenStreetMap contributors\n");
        attributionText.append("  https://www.openstreetmap.org/copyright\n");

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Map Attribution")
                .setMessage(attributionText.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void updateFabIcons() {
        if (fabLocate != null) {
            // Different icons for different tracking modes
            switch (currentTrackingMode) {
                case TRACK:
                    fabLocate.setImageResource(android.R.drawable.ic_menu_mylocation);
                    fabLocate.setAlpha(1.0f);
                    break;
                case SHOW:
                    fabLocate.setImageResource(android.R.drawable.ic_menu_mylocation);
                    fabLocate.setAlpha(0.7f);
                    break;
                case OFF:
                default:
                    fabLocate.setImageResource(android.R.drawable.ic_menu_mylocation);
                    fabLocate.setAlpha(0.4f);
                    break;
            }
        }
        
        if (fabBearing != null) {
            // When tracking is OFF, dim the bearing button since it has no effect
            float baseAlpha = (currentTrackingMode == TrackingMode.OFF) ? 0.3f : 1.0f;
            
            // Different appearance for different bearing modes
            switch (currentBearingMode) {
                case COMPASS:
                    fabBearing.setImageResource(R.drawable.ic_compass_diamond);
                    fabBearing.setAlpha(baseAlpha);
                    break;
                case GPS:
                    fabBearing.setImageResource(R.drawable.ic_navigation_arrow);
                    fabBearing.setAlpha(baseAlpha);
                    break;
                case NORTH:
                default:
                    fabBearing.setImageResource(R.drawable.ic_compass_diamond);
                    // North mode is dimmer to indicate "default/inactive rotation"
                    fabBearing.setAlpha(baseAlpha * 0.5f);
                    break;
            }
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
