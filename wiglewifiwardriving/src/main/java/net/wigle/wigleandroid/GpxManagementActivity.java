package net.wigle.wigleandroid;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.LatLng;
// Note: Polyline handling remains using Google Maps types; consider migrating to MapLibre polyline equivalents when ready

import net.wigle.wigleandroid.background.GpxExportRunnable;
import net.wigle.wigleandroid.db.DBException;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.PolylineRoute;
import net.wigle.wigleandroid.ui.GpxRecyclerAdapter;
import net.wigle.wigleandroid.ui.ScreenChildActivity;
import net.wigle.wigleandroid.ui.ThemeUtil;
import net.wigle.wigleandroid.ui.UINumberFormat;
import net.wigle.wigleandroid.ui.WiGLEToast;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PolyRouteConfigurable;
import net.wigle.wigleandroid.util.PreferenceKeys;
import net.wigle.wigleandroid.util.RouteExportSelector;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import static android.view.View.GONE;
import static net.wigle.wigleandroid.background.GpxExportRunnable.EXPORT_GPX_DIALOG;

public class GpxManagementActivity extends ScreenChildActivity implements PolyRouteConfigurable, RouteExportSelector, DialogListener {

    private final NumberFormat numberFormat;
    private final int DEFAULT_MAP_PADDING = 25;
    private final DatabaseHelper dbHelper;
    private MapView mapView;
    private View infoView;
    private TextView distanceText;
    private String routePolyline;
    private final String CURRENT_ROUTE_LINE_TAG = "currentRoutePolyline";
    private SharedPreferences prefs;
    private long exportRouteId = -1L;

    public GpxManagementActivity() {
        final MainActivity.State s = MainActivity.getStaticState();
        if (null != s) {
            this.dbHelper = s.dbHelper;
        } else {
            this.dbHelper = null;
        }
        Locale locale = Locale.getDefault();
        numberFormat = NumberFormat.getNumberInstance(locale);
        numberFormat.setMaximumFractionDigits(1);
    }

    @Override
    public void onCreate( final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gpx_mgmt);
        EdgeToEdge.enable(this);
        View backButtonWrapper = findViewById(R.id.gpx_back_layout);
        if (null != backButtonWrapper) {
            ViewCompat.setOnApplyWindowInsetsListener(backButtonWrapper, new OnApplyWindowInsetsListener() {
                        @Override
                        public @org.jspecify.annotations.NonNull WindowInsetsCompat onApplyWindowInsets(@org.jspecify.annotations.NonNull View v, @org.jspecify.annotations.NonNull WindowInsetsCompat insets) {
                            final Insets innerPadding = insets.getInsets(
                                    WindowInsetsCompat.Type.statusBars() |
                                            WindowInsetsCompat.Type.displayCutout());
                            v.setPadding(
                                    innerPadding.left, innerPadding.top, innerPadding.right, innerPadding.bottom
                            );
                            return insets;
                        }
                    }
            );
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        ImageButton backButton = findViewById(R.id.gpx_back_button);
        if (null != backButton) {
            backButton.setOnClickListener(v -> finish());
        }
        prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        setupMap(prefs);
        setupList();
    }

    @Override
    public void onDestroy() {
        Logging.info("NET: onDestroy");
        if (mapView != null) {
            mapView.onDestroy();
        }
        super.onDestroy();
        //setResult(Result.OK);
        finish();
    }

    @Override
    public void onResume() {
        Logging.info("NET: onResume");
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        } else {
            final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            setupMap(prefs);
        }
    }

    @Override
    public void onPause() {
        Logging.info("NET: onPause");
        super.onPause();
        if (mapView != null) {
            mapView.onPause();
        }
    }

    private void setupMap(final SharedPreferences prefs) {
        try { MapLibre.getInstance(this); } catch (Exception ignored) {}
        mapView = new MapView( this );
        try {
            mapView.onCreate(null);
                mapView.getMapAsync((MapLibreMap mapLibreMap) -> ThemeUtil.setMapTheme(mapLibreMap, mapView.getContext(), prefs, R.raw.night_style_json));
        } catch (NullPointerException ex) {
            Logging.error("npe in mapView.onCreate: " + ex, ex);
        }
        // MapLibre does not require MapsInitializer; style applied in getMapAsync
        final RelativeLayout rlView = findViewById( R.id.gpx_map_rl );
        rlView.addView( mapView );
        infoView = findViewById(R.id.gpx_info);
        distanceText = findViewById(R.id.gpx_rundistance);
    }

    @Override
    public void configureMapForRoute(final PolylineRoute polyRoute) {
        if ((polyRoute != null)) {
            mapView.getMapAsync((MapLibreMap mapLibreMap) -> {
                LatLngBounds.Builder builder = new LatLngBounds.Builder();
                builder.include(polyRoute.getNEExtent());
                builder.include(polyRoute.getSWExtent());
                LatLngBounds bounds = builder.build();
                final CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, DEFAULT_MAP_PADDING);
                mapLibreMap.animateCamera(cu);
                // add route as GeoJSON LineString + LineLayer
                final String srcId = CURRENT_ROUTE_LINE_TAG + "_src";
                final String layerId = CURRENT_ROUTE_LINE_TAG + "_layer";
                final LineString ls = polyRoute.getLineString();
                final Feature feature = Feature.fromGeometry(ls);
                final FeatureCollection fc = FeatureCollection.fromFeatures(new Feature[]{feature});
                final GeoJsonSource existing = mapLibreMap.getStyle().getSourceAs(srcId);
                if (existing != null) {
                    existing.setGeoJson(fc);
                } else {
                    final GeoJsonSource src = new GeoJsonSource(srcId, fc);
                    mapLibreMap.getStyle().addSource(src);
                    final LineLayer layer = new LineLayer(layerId, srcId);
                    layer.setProperties(PropertyFactory.lineColor(MappingFragment.getRouteColorForMapType(MapTypes.MAP_TYPE_NORMAL, false)), PropertyFactory.lineWidth(PolylineRoute.DEFAULT_ROUTE_WIDTH));
                    mapLibreMap.getStyle().addLayer(layer);
                }
                routePolyline = srcId;
            });
            infoView.setVisibility(View.VISIBLE);
            final String distString = UINumberFormat.metersToString(prefs,
                    numberFormat, this, polyRoute.getDistanceMeters(), true);
            distanceText.setText(distString);
        } else {
            infoView.setVisibility(GONE);
            distanceText.setText("");
        }
    }

    @Override
    public void clearCurrentRoute() {
        if (routePolyline != null ) {
            final String layerId = CURRENT_ROUTE_LINE_TAG + "_layer";
            final String srcId = routePolyline;
            mapView.getMapAsync((MapLibreMap mapLibreMap) -> {
                if (mapLibreMap.getStyle() != null) {
                    if (mapLibreMap.getStyle().getLayer(layerId) != null) {
                        mapLibreMap.getStyle().removeLayer(layerId);
                    }
                    if (mapLibreMap.getStyle().getSourceAs(srcId) != null) {
                        mapLibreMap.getStyle().removeSource(srcId);
                    }
                }
            });
            routePolyline = null;
        }
    }

    private void setupList() {
        RecyclerView recyclerView = findViewById(R.id.gpx_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        if (null != dbHelper) {
            try {
                Cursor cursor = dbHelper.routeMetaIterator();
                final DateFormat itemDateFormat = android.text.format.DateFormat.getDateFormat(this.getApplicationContext());
                final DateFormat itemTimeFormat = android.text.format.DateFormat.getTimeFormat(this.getApplicationContext());
                GpxRecyclerAdapter adapter = new GpxRecyclerAdapter(this, this, cursor, this, this, prefs, itemDateFormat, itemTimeFormat);
                recyclerView.setAdapter(adapter);
            } catch (DBException dbex) {
                Logging.error("Failed to setup list for GPX management: ", dbex);
            }
        }
    }

    @Override
    public void handleDialog(int dialogId) {
        switch (dialogId) {
            case EXPORT_GPX_DIALOG: {
                if (!exportRouteGpxFile(exportRouteId)) {
                    Logging.warn("Failed to export gpx.");
                    //WiGLEToast.showOverFragment(this, R.string.error_general,
                    //        getString(R.string.gpx_failed));
                }
                break;
            }
            default:
                Logging.warn("Data unhandled dialogId: " + dialogId);
        }
    }

    private boolean exportRouteGpxFile(long runId) {
        final long totalRoutePoints = ListFragment.lameStatic.dbHelper.getRoutePointCount(runId);
        if (totalRoutePoints > 1) {
            ExecutorService es = ListFragment.lameStatic.executorService;
            if (null != es) {
                try {
                    es.submit(new GpxExportRunnable(this, true, totalRoutePoints, runId));
                } catch (IllegalArgumentException e) {
                    Logging.error("failed to submit job: ", e);
                    WiGLEToast.showOverFragment(this, R.string.export_gpx,
                            getResources().getString(R.string.duplicate_job));
                    return false;
                }
            } else {
                Logging.error("null LameStatic ExecutorService - unable to submit route export");
            }
        } else {
            Logging.error("no points to create route");
            WiGLEToast.showOverFragment(this, R.string.gpx_failed,
                    getResources().getString(R.string.gpx_no_points));
            //NO POINTS
        }
        return true;
    }

    @Override
    public void setRouteToExport(long routeId) {
        exportRouteId = routeId;
    }
}