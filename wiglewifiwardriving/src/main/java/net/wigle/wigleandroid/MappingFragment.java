package net.wigle.wigleandroid;

import static android.view.View.GONE;
import net.wigle.wigleandroid.MapTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.goebl.simplify.PointExtractor;
import com.goebl.simplify.Simplify;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.layers.LineLayer;

import net.wigle.wigleandroid.background.PooledQueryExecutor;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.ConcurrentLinkedHashMap;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.net.WiGLEApiManager;
import net.wigle.wigleandroid.ui.PrefsBackedCheckbox;
import net.wigle.wigleandroid.ui.ThemeUtil;
import net.wigle.wigleandroid.ui.UINumberFormat;
import net.wigle.wigleandroid.ui.WiGLEToast;
import net.wigle.wigleandroid.util.FilterUtil;
import net.wigle.wigleandroid.util.HeadingManager;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;
import net.wigle.wigleandroid.util.StatsUtil;

import static net.wigle.wigleandroid.listener.GNSSListener.MIN_ROUTE_LOCATION_DIFF_METERS;
import static net.wigle.wigleandroid.listener.GNSSListener.MIN_ROUTE_LOCATION_DIFF_TIME;
import static net.wigle.wigleandroid.listener.GNSSListener.MIN_ROUTE_LOCATION_PRECISION_METERS;
import static net.wigle.wigleandroid.ui.PrefsBackedCheckbox.BT_SUB_BOX_IDS;
import static net.wigle.wigleandroid.ui.PrefsBackedCheckbox.WIFI_SUB_BOX_IDS;

/**
 * show a map depicting current position and configurable stumbling progress information.
 */
public final class MappingFragment extends Fragment {

    private final String ROUTE_LINE_TAG = "routePolyline";

    private static class State {
        private boolean locked = true;
        private boolean firstMove = true;
        private LatLng oldCenter = null;
    }

    private final State state = new State();

    private MapView mapView;
    private MapRender mapRender;

    private final Handler timer = new Handler();
    private AtomicBoolean finishing;
    private Location previousLocation;
    private int previousRunNets;
    private String tileSourceId = null;
    private String tileLayerId = null;
    private Object routePolyline;
    private java.util.List<Point> routePoints = new java.util.ArrayList<>();
    private Location lastLocation;

    private HeadingManager headingManager;

    private Menu menu;

    private static final String DIALOG_PREFIX = "DialogPrefix";
    public static final String MAP_DIALOG_PREFIX = "";
    public static LocationListener STATIC_LOCATION_LISTENER = null;

    private NumberFormat numberFormat;

    static final int UPDATE_MAP_FILTER = 1;

    private static final int DEFAULT_ZOOM = 17;
    public static final LatLng DEFAULT_POINT = new LatLng(41.95d, -87.65d);
    private static final int MENU_ZOOM_IN = 13;
    private static final int MENU_ZOOM_OUT = 14;
    private static final int MENU_TOGGLE_LOCK = 15;
    private static final int MENU_TOGGLE_NEWDB = 16;
    private static final int MENU_LABEL = 17;
    private static final int MENU_FILTER = 18;
    private static final int MENU_CLUSTER = 19;
    private static final int MENU_TRAFFIC = 20;
    private static final int MENU_MAP_TYPE = 21;
    private static final int MENU_WAKELOCK = 22;
    // ALIBI: 15% is actually pretty acceptable for map orientation.
    private static final float MIN_BEARING_UPDATE_ACCURACY = 54.1f;

    private static final String MAP_TILE_URL_FORMAT =
            "https://wigle.net/clientTile?zoom=%d&x=%d&y=%d&startTransID=%s&endTransID=%s";

    private static final String HIGH_RES_TILE_TRAILER = "&sizeX=512&sizeY=512";
    private static final String ONLY_MINE_TILE_TRAILER = "&onlymine=1";
    private static final String NOT_MINE_TILE_TRAILER = "&notmine=1";

    // parameters for polyline simplification package: https://github.com/hgoebl/simplify-java
    // ALIBI: we could tighten these parameters significantly, but it results in wonky over-
    //   simplifications leading up to the present position (since there are no "subsequent" values
    //   to offset the algo's propensity to over-optimize the "end" cap.)
    // Values chosen not to overburden most modern Android phones capabilities.
    // assume we need to undertake drastic route line complexity if we exceed this many segments
    private static final int POLYLINE_PERF_THRESHOLD_COARSE = 15000;
    // perform minor route line complexity simplification if we exceed this many segments
    private static final int POLYLINE_PERF_THRESHOLD_FINE = 5000;

    // when performing drastic polyline simplification (Radial-Distance), this is our "tolerance value"
    private static final float POLYLINE_TOLERANCE_COARSE = 20.0f;
    // when performing minor polyline simplification (Douglas-Peucker), this is our "tolerance value"
    private static final float POLYLINE_TOLERANCE_FINE = 50.0f;

    private static final float ROUTE_WIDTH = 20.0f;
    private static final int OVERLAY_DARK = Color.BLACK;
    private static final int OVERLAY_LIGHT = Color.parseColor("#F4D03F");


    /** Called when the activity is first created. */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        Logging.info("MAP: onCreate");
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // set language
        final Activity a = getActivity();
        if (null != a) {
            MainActivity.setLocale(a);
            a.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        }
        finishing = new AtomicBoolean(false);
        final Configuration conf = getResources().getConfiguration();
        Locale locale = null;
        if (null != conf && null != conf.getLocales()) {
            locale = conf.getLocales().get(0);
        }
        if (null == locale) {
            locale = Locale.US;
        }
        numberFormat = NumberFormat.getNumberInstance(locale);
        numberFormat.setMaximumFractionDigits(2);
        final SharedPreferences prefs = (null != a)?a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0):null;
        if (prefs != null && BuildConfig.DEBUG && HeadingManager.DEBUG && prefs.getBoolean(PreferenceKeys.PREF_MAP_FOLLOW_BEARING, false)) {
            headingManager = new HeadingManager(a);
        }
        setupQuery();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Activity a = getActivity();
        if (null != a) {
            mapView = new MapView(a);
            try {
                mapView.onCreate(savedInstanceState);
                final SharedPreferences prefs = a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                mapView.getMapAsync(mapLibreMap -> ThemeUtil.setMapTheme(mapLibreMap, mapView.getContext(), prefs, R.raw.night_style_json));
            }
            catch (final SecurityException ex) {
                Logging.error("security exception onCreateView map: " + ex, ex);
            }
        }
        final View view = inflater.inflate(R.layout.map, container, false);

        final LatLng oldCenter = state.oldCenter != null ? state.oldCenter : null;
        int oldZoom = Integer.MIN_VALUE;
        setupMapView(view, oldCenter, oldZoom);
        return view;
    }

    @SuppressLint("MissingPermission")
    private void setupMapView(final View view, final LatLng oldCenter, final int oldZoom) {
        // view
        final RelativeLayout rlView = view.findViewById(R.id.map_rl);
        if (mapView != null) {
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mapView.setLayoutParams(params);
        }

        // conditionally replace the tile source
        final Activity a = getActivity();
        final SharedPreferences prefs = (null != a)?a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0):null;
        final boolean visualizeRoute = prefs != null && prefs.getBoolean(PreferenceKeys.PREF_VISUALIZE_ROUTE, false);
        rlView.addView(mapView);
        // initialize MapLibre map and the MapRender adapter
        mapView.getMapAsync(mapLibreMap -> {
            final Activity a1 = getActivity();
            if (null != a1) {
                mapRender = new MapRender(a1, mapLibreMap, false);
            }

            // controller: center map
            final LatLng centerPoint = getCenter(getActivity(), oldCenter, previousLocation);
            float zoom = DEFAULT_ZOOM;
            if (oldZoom >= 0) {
                zoom = oldZoom;
            } else {
                if (null != prefs) {
                    zoom = prefs.getFloat(PreferenceKeys.PREF_PREV_ZOOM, zoom);
                }
            }

            final org.maplibre.android.camera.CameraPosition cameraPosition = new org.maplibre.android.camera.CameraPosition.Builder()
                    .target(centerPoint).zoom(zoom).build();
            mapLibreMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(cameraPosition));


            // Tile overlays implemented previously with Google Maps TileProvider/TileOverlay.
            // MapLibre handles raster tile sources differently (RasterSource/TileSet + RasterLayer)
            // For now, skip adding a Google-style TileOverlay when running under MapLibre.
            // TODO: migrate server tile logic to a MapLibre RasterSource if authentication and headers can be expressed.

            //ALIBI: still checking prefs because we pass them to the dbHelper
            if (null != prefs  && visualizeRoute) {

                final int mapMode = prefs.getInt(PreferenceKeys.PREF_MAP_TYPE, MapTypes.MAP_TYPE_NORMAL);
                final boolean nightMode = ThemeUtil.shouldUseMapNightMode(getContext(), prefs);
                try (Cursor routeCursor = ListFragment.lameStatic.dbHelper
                        .getCurrentVisibleRouteIterator(prefs)) {
                    if (null == routeCursor) {
                        Logging.info("null route cursor; not mapping");
                    } else {
                        long segmentCount = 0;
                        final java.util.List<Point> points = new java.util.ArrayList<>();

                        for (routeCursor.moveToFirst(); !routeCursor.isAfterLast(); routeCursor.moveToNext()) {
                            final double lat = routeCursor.getDouble(0);
                            final double lon = routeCursor.getDouble(1);
                            //final long time = routeCursor.getLong(2);
                            points.add(Point.fromLngLat(lon, lat));
                            segmentCount++;
                        }
                        Logging.info("Loaded route with " + segmentCount + " segments");

                        final String srcId = "route_source";
                        final String layerId = "route_layer";
                        // store collected points for live updates
                        synchronized (this) {
                            this.routePoints = points;
                        }
                        mapView.getMapAsync(mlMap -> {
                            if (mlMap.getStyle() != null) {
                                final Feature feature = Feature.fromGeometry(LineString.fromLngLats(points));
                                final FeatureCollection fc = FeatureCollection.fromFeatures(new Feature[]{feature});
                                final GeoJsonSource existing = mlMap.getStyle().getSourceAs(srcId);
                                if (existing != null) {
                                    existing.setGeoJson(fc);
                                } else {
                                    final GeoJsonSource src = new GeoJsonSource(srcId, fc);
                                    mlMap.getStyle().addSource(src);
                                    final LineLayer layer = new LineLayer(layerId, srcId);
                                    layer.setProperties(PropertyFactory.lineColor(getRouteColorForMapType(mapMode, nightMode)), PropertyFactory.lineWidth(ROUTE_WIDTH));
                                    mlMap.getStyle().addLayer(layer);
                                }
                                routePolyline = srcId;
                            } else {
                                Logging.info("Style not ready; route will be applied when style loads.");
                                routePolyline = srcId;
                            }
                        });
                    }
                } catch (Exception e) {
                    Logging.error("Unable to add route: ",e);
                }
            }
        });
        Logging.info("done setupMapView.");
    }

    public Float getBearing( final Context context) {
        final Location gpsLocation = safelyGetLast(context, LocationManager.GPS_PROVIDER);
        if (gpsLocation != null) {
            //DEBUG: Logging.info("acc: "+headingManager.getAccuracy());
            final Float bearing = (gpsLocation.hasBearing() && gpsLocation.getBearing() != 0.0f)?gpsLocation.getBearing():null;

            if (null != bearing) {
                //ALIBI: prefer bearing if it's not garbage, because heading almost certainly is.
                if (gpsLocation.hasAccuracy() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)) {
                    if (gpsLocation.getBearingAccuracyDegrees() < MIN_BEARING_UPDATE_ACCURACY) {
                        return gpsLocation.getBearing();
                    }
                } else {
                    Logging.warn("have GPS location but no headingManager or accuracy");
                    return bearing;
                }
            }
            //ALIBI: heading is too often completely wrong. This is here for debugging only unless things improve.
            if (null != headingManager && BuildConfig.DEBUG && HeadingManager.DEBUG  && headingManager.getAccuracy() >= 3.0) {
                // if the fusion of accelerometer and magnetic compass claims it doesn't suck (although it probably still does)
                return headingManager.getHeading(gpsLocation);
            }
        }
        return null;
    }

    public static LatLng getCenter( final Context context, final LatLng priorityCenter,
                                    final Location previousLocation ) {

        LatLng centerPoint = DEFAULT_POINT;
        final Location location = ListFragment.lameStatic.location;
        final SharedPreferences prefs = context.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );

        if ( priorityCenter != null ) {
            centerPoint = priorityCenter;
        }
        else if ( location != null ) {
            centerPoint = new LatLng( location.getLatitude(), location.getLongitude() );
        }
        else if ( previousLocation != null ) {
            centerPoint = new LatLng( previousLocation.getLatitude(), previousLocation.getLongitude() );
        }
        else {
            final Location gpsLocation = safelyGetLast(context, LocationManager.GPS_PROVIDER);
            final Location networkLocation = safelyGetLast(context, LocationManager.NETWORK_PROVIDER);

            if ( gpsLocation != null ) {
                centerPoint = new LatLng( gpsLocation.getLatitude(), gpsLocation.getLongitude()  );
            }
            else if ( networkLocation != null ) {
                centerPoint = new LatLng( networkLocation.getLatitude(), networkLocation.getLongitude()  );
            }
            else {
                // ok, try the saved prefs
                float lat = prefs.getFloat( PreferenceKeys.PREF_PREV_LAT, Float.MIN_VALUE );
                float lon = prefs.getFloat( PreferenceKeys.PREF_PREV_LON, Float.MIN_VALUE );
                if ( lat != Float.MIN_VALUE && lon != Float.MIN_VALUE ) {
                    centerPoint = new LatLng( lat, lon );
                }
            }
        }

        return centerPoint;
    }

    @SuppressLint("MissingPermission")
    private static Location safelyGetLast(final Context context, final String provider ) {
        Location retval = null;
        try {
            final LocationManager locationManager = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            retval = locationManager.getLastKnownLocation( provider );
        }
        catch ( final IllegalArgumentException | SecurityException ex ) {
            Logging.info("exception getting last known location: " + ex);
        }
        return retval;
    }

    final Runnable mUpdateTimeTask = new MapRunnable();
    private class MapRunnable implements Runnable {
        @Override
        public void run() {
            final View view = getView();
            final Activity a = getActivity();
            final SharedPreferences prefs = a != null?a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0):null;
            // make sure the app isn't trying to finish
            if ( ! finishing.get() ) {
                final Location location = ListFragment.lameStatic.location;
                if ( location != null ) {
                    if ( state.locked ) {
                        mapView.getMapAsync(mapLibreMap -> {
                            final LatLng locLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                            float currentZoom = (float) mapLibreMap.getCameraPosition().zoom;
                            Float cameraBearing = null;
                            if (null != prefs && prefs.getBoolean(PreferenceKeys.PREF_MAP_FOLLOW_BEARING, false)) {
                                cameraBearing = getBearing(a);
                            }
                            final org.maplibre.android.camera.CameraUpdate centerUpdate = (state.firstMove || cameraBearing == null) ?
                                    org.maplibre.android.camera.CameraUpdateFactory.newLatLng(locLatLng) :
                                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                            new org.maplibre.android.camera.CameraPosition.Builder().bearing(cameraBearing).zoom(currentZoom).target(locLatLng).build());
                            if (state.firstMove) {
                                mapLibreMap.moveCamera(centerUpdate);
                                state.firstMove = false;
                            } else {
                                mapLibreMap.animateCamera(centerUpdate);
                            }
                        });
                    }
                    else if ( previousLocation == null || previousLocation.getLatitude() != location.getLatitude()
                            || previousLocation.getLongitude() != location.getLongitude()
                            || previousRunNets != ListFragment.lameStatic.runNets) {
                        // location or nets have changed, update the view
                        if (mapView != null) {
                            mapView.postInvalidate();
                        }
                    }

                    try {
                        final boolean showRoute = prefs != null && prefs.getBoolean(PreferenceKeys.PREF_VISUALIZE_ROUTE, false);
                        //DEBUG: MainActivity.info("mUpdateTimeTask with non-null location. show: "+showRoute);
                        if (showRoute) {
                            double accuracy = location.getAccuracy();
                            if (location.getTime() != 0 &&
                                    accuracy < MIN_ROUTE_LOCATION_PRECISION_METERS
                                    && accuracy > 0.0d &&
                                    (lastLocation == null ||
                                            ((location.getTime() - lastLocation.getTime()) > MIN_ROUTE_LOCATION_DIFF_TIME) &&
                                                    lastLocation.distanceTo(location)> MIN_ROUTE_LOCATION_DIFF_METERS)) {
                                if (routePolyline != null) {
                                    // support two possible route representations:
                                    // - a String source id when using MapLibre GeoJsonSource
                                    // - a legacy Google Polyline object (left for compatibility)
                                    if (routePolyline instanceof String) {
                                        final String srcId = (String) routePolyline;
                                        routePoints.add(Point.fromLngLat(location.getLongitude(), location.getLatitude()));
                                        mapView.getMapAsync(mapLibreMap -> {
                                            if (mapLibreMap.getStyle() != null) {
                                                final GeoJsonSource src = mapLibreMap.getStyle().getSourceAs(srcId);
                                                if (src != null) {
                                                    final Feature feature = Feature.fromGeometry(LineString.fromLngLats(routePoints));
                                                    final FeatureCollection fc = FeatureCollection.fromFeatures(new Feature[]{feature});
                                                    src.setGeoJson(fc);
                                                }
                                            }
                                        });
                                    } else {
                                        // legacy Google Polyline path - skip in MapLibre build
                                        Logging.info("Legacy polyline update skipped in MapLibre build");
                                    }
                                } else {
                                    Logging.error("route polyline null - this shouldn't happen");
                                }
                                lastLocation = location;
                            } else {
                                //DEBUG:    MainActivity.warn("time/accuracy route update DQ");
                            }
                        }
                    } catch (Exception ex) {
                        Logging.error("Route point update failed: ",ex);
                    }

                    // set if location isn't null
                    previousLocation = location;
                }


                previousRunNets = ListFragment.lameStatic.runNets;

                if (view != null) {
                    TextView tv = view.findViewById(R.id.stats_wifi);
                    tv.setText( UINumberFormat.counterFormat(ListFragment.lameStatic.newWifi) );
                    tv = view.findViewById( R.id.stats_cell );
                    tv.setText( UINumberFormat.counterFormat(ListFragment.lameStatic.newCells)  );
                    tv = view.findViewById( R.id.stats_bt );
                    tv.setText( UINumberFormat.counterFormat(ListFragment.lameStatic.newBt)  );
                    if (null != prefs) {
                        final long unUploaded = StatsUtil.newNetsSinceUpload(prefs);
                        tv = view.findViewById(R.id.stats_unuploaded);
                        tv.setText(UINumberFormat.counterFormat(unUploaded));
                    }
                    tv = view.findViewById(R.id.heading);
                    final Location gpsLocation = safelyGetLast(getContext(), LocationManager.GPS_PROVIDER);
                    if (BuildConfig.DEBUG && HeadingManager.DEBUG) {
                        tv.setText(String.format(Locale.ROOT, "heading: %3.2f", ((headingManager != null) ? headingManager.getHeading(gpsLocation) : -1f)));
                        if (null != ListFragment.lameStatic.location) {
                            tv = view.findViewById(R.id.bearing);
                            if (gpsLocation.hasAccuracy() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)) {
                                tv.setText(String.format(Locale.ROOT,"bearing: %3.2f +/- %3.2f", ListFragment.lameStatic.location.getBearing(), ListFragment.lameStatic.location.getBearingAccuracyDegrees()));
                            } else {
                                tv.setText(String.format(Locale.ROOT,"bearing: %3.2f", ListFragment.lameStatic.location.getBearing()));
                            }
                        }
                        tv = view.findViewById(R.id.selectedbh);
                        tv.setText(String.format(Locale.ROOT,"chose: %3.2f", getBearing(getContext())));
                    } else {
                        final View v =view.findViewById(R.id.debug);
                        if (null != v) {
                            v.setVisibility(GONE);
                        }
                    }

                    tv = view.findViewById( R.id.stats_dbnets );
                    tv.setText(UINumberFormat.counterFormat(ListFragment.lameStatic.dbNets));
                    if (prefs != null) {
                        float dist = prefs.getFloat(PreferenceKeys.PREF_DISTANCE_RUN, 0f);
                        final String distString = UINumberFormat.metersToString(prefs,
                                numberFormat, getActivity(), dist, true);
                        tv = view.findViewById(R.id.rundistance);
                        tv.setText(distString);
                    }
                }

                final long period = 1000L;
                // info("wifitimer: " + period );
                timer.postDelayed( this, period );
            }
            else {
                Logging.info( "finishing mapping timer" );
            }
        }
    }

    private void setupTimer() {
        timer.removeCallbacks( mUpdateTimeTask );
        timer.postDelayed( mUpdateTimeTask, 250 );
    }

    @Override
    public void onDetach() {
        Logging.info( "MAP: onDetach.");
        super.onDetach();
    }

    @Override
    public void onDestroy() {
        Logging.info( "MAP: destroy mapping." );
        finishing.set(true);

        mapView.getMapAsync(mapLibreMap -> {
            // save zoom
            final Activity a = getActivity();
            if (null != a) {
            final SharedPreferences prefs = a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            if (null != prefs) {
                final Editor edit = prefs.edit();
                edit.putFloat(PreferenceKeys.PREF_PREV_ZOOM, (float) mapLibreMap.getCameraPosition().zoom);
                edit.apply();
            } else {
                Logging.warn("failed saving map state - unable to get preferences.");
            }
            // save center
            state.oldCenter = mapLibreMap.getCameraPosition().target;
            }
        });
        try {
            mapView.onDestroy();
        } catch (NullPointerException ex) {
            // seen in the wild
            Logging.info("exception in mapView.onDestroy: " + ex, ex);
        }

        super.onDestroy();
    }

    @Override
    public void onPause() {
        Logging.info("MAP: onPause");
        super.onPause();
        try {
            mapView.onPause();
        } catch (final NullPointerException ex) {
            Logging.error("npe on mapview pause: " + ex, ex);
        }
        if (mapRender != null) {
            // save memory
            mapRender.clear();
        }
        if (null != headingManager) {
                headingManager.stopSensor();
        }
    }

    @Override
    public void onResume() {
        Logging.info( "MAP: onResume" );
        super.onResume();
        if (mapRender != null) {
            mapRender.onResume();
        }
        // MapLibre handles raster layers differently; no Google TileOverlay to refresh here.

        setupTimer();
        final Activity a = getActivity();
        if (null != a) {
            a.setTitle(R.string.mapping_app_name);
        }
        if (null != headingManager) {
            headingManager.startSensor();
        }
        mapView.onResume();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        Logging.info( "MAP: onSaveInstanceState" );
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        Logging.info( "MAP: onLowMemory" );
        super.onLowMemory();
        mapView.onLowMemory();
    }

    public void addNetwork(final Network network) {
        if (mapRender != null && mapRender.okForMapTab(network)) {
            mapRender.addItem(network);
        }
    }

    public void updateNetwork(final Network network) {
        if (mapRender != null) {
            mapRender.updateNetwork(network);
        }
    }

    public void reCluster() {
        if (mapRender != null) {
            mapRender.reCluster();
        }
    }

    /* Creates the menu items */
    @Override
    public void onCreateOptionsMenu (@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        Logging.info( "MAP: onCreateOptionsMenu" );
        MenuItem item;
        final Activity a = getActivity();
        if (null != a) {
            final SharedPreferences prefs = a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            final boolean showNewDBOnly = prefs.getBoolean(PreferenceKeys.PREF_MAP_ONLY_NEWDB, false);
            final boolean showLabel = prefs.getBoolean(PreferenceKeys.PREF_MAP_LABEL, true);
            final boolean showCluster = prefs.getBoolean(PreferenceKeys.PREF_MAP_CLUSTER, true);
            final boolean showTraffic = prefs.getBoolean(PreferenceKeys.PREF_MAP_TRAFFIC, true);

            String nameLabel = showLabel ? getString(R.string.menu_labels_off) : getString(R.string.menu_labels_on);
            item = menu.add(0, MENU_LABEL, 0, nameLabel);
            item.setIcon(android.R.drawable.ic_dialog_info);

            String nameCluster = showCluster ? getString(R.string.menu_cluster_off) : getString(R.string.menu_cluster_on);
            item = menu.add(0, MENU_CLUSTER, 0, nameCluster);
            item.setIcon(android.R.drawable.ic_menu_add);

            String nameTraffic = showTraffic ? getString(R.string.menu_traffic_off) : getString(R.string.menu_traffic_on);
            item = menu.add(0, MENU_TRAFFIC, 0, nameTraffic);
            item.setIcon(android.R.drawable.ic_menu_directions);

            item = menu.add(0, MENU_MAP_TYPE, 0, getString(R.string.menu_map_type));
            item.setIcon(R.drawable.map_layer);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

            item = menu.add(0, MENU_FILTER, 0, getString(R.string.settings_map_head));
            item.setIcon(R.drawable.filter);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

            String name = state.locked ? getString(R.string.menu_turn_off_lockon) : getString(R.string.menu_turn_on_lockon);
            item = menu.add(0, MENU_TOGGLE_LOCK, 0, name);
            item.setIcon(android.R.drawable.ic_lock_lock);

            String nameDB = showNewDBOnly ? getString(R.string.menu_show_old) : getString(R.string.menu_show_new);
            item = menu.add(0, MENU_TOGGLE_NEWDB, 0, nameDB);
            item.setIcon(android.R.drawable.ic_menu_edit);
        }
        final String wake = MainActivity.isScreenLocked( this ) ?
                getString(R.string.menu_screen_sleep) : getString(R.string.menu_screen_wake);
        item = menu.add(0, MENU_WAKELOCK, 0, wake);
        item.setIcon( android.R.drawable.ic_menu_gallery );

        super.onCreateOptionsMenu(menu, inflater);
        this.menu = menu;
    }

    /* Handles item selections */
    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item ) {
        final Activity a = getActivity();
        if (null != a) {
            final SharedPreferences prefs = a.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            switch (item.getItemId()) {
                case MENU_ZOOM_IN: {
                    mapView.getMapAsync(mapLibreMap -> {
                        float zoom = (float) mapLibreMap.getCameraPosition().zoom;
                        zoom++;
                        final org.maplibre.android.camera.CameraUpdate zoomUpdate = org.maplibre.android.camera.CameraUpdateFactory.zoomTo(zoom);
                        mapLibreMap.animateCamera(zoomUpdate);
                    });
                    return true;
                }
                case MENU_ZOOM_OUT: {
                    mapView.getMapAsync(mapLibreMap -> {
                        float zoom = (float) mapLibreMap.getCameraPosition().zoom;
                        zoom--;
                        final org.maplibre.android.camera.CameraUpdate zoomUpdate = org.maplibre.android.camera.CameraUpdateFactory.zoomTo(zoom);
                        mapLibreMap.animateCamera(zoomUpdate);
                    });
                    return true;
                }
                case MENU_TOGGLE_LOCK: {
                    state.locked = !state.locked;
                    String name = state.locked ? getString(R.string.menu_turn_off_lockon) : getString(R.string.menu_turn_on_lockon);
                    item.setTitle(name);
                    return true;
                }
                case MENU_TOGGLE_NEWDB: {
                    final boolean showNewDBOnly = !prefs.getBoolean(PreferenceKeys.PREF_MAP_ONLY_NEWDB, false);
                    Editor edit = prefs.edit();
                    edit.putBoolean(PreferenceKeys.PREF_MAP_ONLY_NEWDB, showNewDBOnly);
                    edit.apply();

                    String name = showNewDBOnly ? getString(R.string.menu_show_old) : getString(R.string.menu_show_new);
                    item.setTitle(name);
                    if (mapRender != null) {
                        mapRender.reCluster();
                    }
                    return true;
                }
                case MENU_LABEL: {
                    final boolean showLabel = !prefs.getBoolean(PreferenceKeys.PREF_MAP_LABEL, true);
                    Editor edit = prefs.edit();
                    edit.putBoolean(PreferenceKeys.PREF_MAP_LABEL, showLabel);
                    edit.apply();

                    String name = showLabel ? getString(R.string.menu_labels_off) : getString(R.string.menu_labels_on);
                    item.setTitle(name);

                    if (mapRender != null) {
                        mapRender.reCluster();
                    }
                    return true;
                }
                case MENU_CLUSTER: {
                    final boolean showCluster = !prefs.getBoolean(PreferenceKeys.PREF_MAP_CLUSTER, true);
                    Editor edit = prefs.edit();
                    edit.putBoolean(PreferenceKeys.PREF_MAP_CLUSTER, showCluster);
                    edit.apply();

                    String name = showCluster ? getString(R.string.menu_cluster_off) : getString(R.string.menu_cluster_on);
                    item.setTitle(name);

                    if (mapRender != null) {
                        mapRender.reCluster();
                    }
                    return true;
                }
                case MENU_TRAFFIC: {
                    final boolean showTraffic = !prefs.getBoolean(PreferenceKeys.PREF_MAP_TRAFFIC, true);
                    Editor edit = prefs.edit();
                    edit.putBoolean(PreferenceKeys.PREF_MAP_TRAFFIC, showTraffic);
                    edit.apply();

                    String name = showTraffic ? getString(R.string.menu_traffic_off) : getString(R.string.menu_traffic_on);
                    item.setTitle(name);
                    mapView.getMapAsync(mapLibreMap -> { /* traffic layer not supported by MapLibre in same way; no-op */ });
                    return true;
                }
                case MENU_FILTER: {
                    final Intent intent = new Intent(getActivity(), MapFilterActivity.class);
                    getActivity().startActivityForResult(intent, UPDATE_MAP_FILTER);
                    return true;
                }
                case MENU_MAP_TYPE: {
                    mapView.getMapAsync(mapLibreMap -> {
                        int newMapType = prefs.getInt(PreferenceKeys.PREF_MAP_TYPE, MapTypes.MAP_TYPE_NORMAL);
                        final Activity a1 = getActivity();
                        switch (newMapType) {
                            case MapTypes.MAP_TYPE_NORMAL:
                                newMapType = MapTypes.MAP_TYPE_SATELLITE;
                                WiGLEToast.showOverActivity(a1, R.string.tab_map, getString(R.string.map_toast_satellite), Toast.LENGTH_SHORT);
                                break;
                            case MapTypes.MAP_TYPE_SATELLITE:
                                newMapType = MapTypes.MAP_TYPE_HYBRID;
                                WiGLEToast.showOverActivity(a1, R.string.tab_map, getString(R.string.map_toast_hybrid), Toast.LENGTH_SHORT);
                                break;
                            case MapTypes.MAP_TYPE_HYBRID:
                                newMapType = MapTypes.MAP_TYPE_TERRAIN;
                                WiGLEToast.showOverActivity(a1, R.string.tab_map, getString(R.string.map_toast_terrain), Toast.LENGTH_SHORT);
                                break;
                            case MapTypes.MAP_TYPE_TERRAIN:
                                newMapType = MapTypes.MAP_TYPE_NORMAL;
                                WiGLEToast.showOverActivity(a1, R.string.tab_map, getString(R.string.map_toast_normal), Toast.LENGTH_SHORT);
                                break;
                            default:
                                Logging.error("unhandled mapType: " + newMapType);
                        }
                        Editor edit = prefs.edit();
                        edit.putInt(PreferenceKeys.PREF_MAP_TYPE, newMapType);
                        edit.apply();
                        // MapLibre doesn't support GoogleMap 'mapType' enums; map style changes should be handled via style URLs.
                    });
                    return true;
                }
                case MENU_WAKELOCK: {
                    boolean screenLocked = !MainActivity.isScreenLocked(this);
                    MainActivity.setLockScreen(this, screenLocked);
                    final String wake = screenLocked ? getString(R.string.menu_screen_sleep) : getString(R.string.menu_screen_wake);
                    item.setTitle(wake);
                    return true;
                }
            }
        }
        return false;
    }

    public static class MapDialogFragment extends DialogFragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            Bundle args = getArguments();
            final String prefix = null != args? args.getString(DIALOG_PREFIX):"";

            final Dialog dialog = getDialog();
            final Activity activity = getActivity();
            final View view = inflater.inflate(R.layout.filterdialog, container);
            if (null != dialog) {
                dialog.setTitle(R.string.ssid_filter_head);
            }
            Logging.info("make new dialog. prefix: " + prefix);
            if (null != activity) {
                final SharedPreferences prefs = activity.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                final EditText regex = view.findViewById(R.id.edit_regex);
                regex.setText(prefs.getString(prefix + PreferenceKeys.PREF_MAPF_REGEX, ""));

                final CheckBox invert = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showinvert,
                        prefix + PreferenceKeys.PREF_MAPF_INVERT, false, prefs);
                final CheckBox open = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showopen,
                        prefix + PreferenceKeys.PREF_MAPF_OPEN, true, prefs, value -> FilterUtil.updateWifiGroupCheckbox(view));
                final CheckBox wep = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showwep,
                        prefix + PreferenceKeys.PREF_MAPF_WEP, true, prefs, value -> FilterUtil.updateWifiGroupCheckbox(view));
                final CheckBox wpa = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showwpa,
                        prefix + PreferenceKeys.PREF_MAPF_WPA, true, prefs, value -> FilterUtil.updateWifiGroupCheckbox(view));
                final CheckBox cell = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showcell,
                        prefix + PreferenceKeys.PREF_MAPF_CELL, true, prefs);
                final CheckBox enabled = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.enabled,
                        prefix + PreferenceKeys.PREF_MAPF_ENABLED, true, prefs);
                final CheckBox btc = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showbtc,
                        prefix + PreferenceKeys.PREF_MAPF_BT, true, prefs, value -> FilterUtil.updateBluetoothGroupCheckbox(view));
                final CheckBox btle = PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showbtle,
                        prefix + PreferenceKeys.PREF_MAPF_BTLE, true, prefs, value -> FilterUtil.updateBluetoothGroupCheckbox(view));

                FilterUtil.updateWifiGroupCheckbox(view);
                FilterUtil.updateBluetoothGroupCheckbox(view);

                Button ok = view.findViewById(R.id.ok_button);
                ok.setOnClickListener(buttonView -> {
                    try {
                        final Editor editor = prefs.edit();
                        editor.putString(prefix + PreferenceKeys.PREF_MAPF_REGEX, regex.getText().toString());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_INVERT, invert.isChecked());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_OPEN, open.isChecked());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_WEP, wep.isChecked());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_WPA, wpa.isChecked());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_CELL, cell.isChecked());
                        editor.putBoolean(prefix + PreferenceKeys.PREF_MAPF_ENABLED, enabled.isChecked());
                        editor.apply();

                        if (null != dialog) {
                            dialog.dismiss();
                        }
                    } catch (Exception ex) {
                        // guess it wasn't there anyways
                        Logging.info("exception dismissing filter dialog: " + ex);
                    }
                });

                Button cancel = view.findViewById(R.id.cancel_button);
                cancel.setOnClickListener(buttonView -> {
                    try {
                        regex.setText(prefs.getString(prefix + PreferenceKeys.PREF_MAPF_REGEX, ""));
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showinvert,
                                prefix + PreferenceKeys.PREF_MAPF_INVERT, false, prefs);
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showopen,
                                prefix + PreferenceKeys.PREF_MAPF_OPEN, true, prefs);
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showwep,
                                prefix + PreferenceKeys.PREF_MAPF_WEP, true, prefs);
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showwpa,
                                prefix + PreferenceKeys.PREF_MAPF_WPA, true, prefs);
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.showcell,
                                prefix + PreferenceKeys.PREF_MAPF_CELL, true, prefs);
                        PrefsBackedCheckbox.prefSetCheckBox(activity, view, R.id.enabled,
                                prefix + PreferenceKeys.PREF_MAPF_ENABLED, true, prefs);

                        if (null != dialog) {
                            dialog.dismiss();
                        }
                    } catch (Exception ex) {
                        // guess it wasn't there anyways
                        Logging.info("exception dismissing filter dialog: " + ex);
                    }
                });
            }
            return view;
        }
    }

    public static DialogFragment createSsidFilterDialog( final String prefix ) {
        final DialogFragment dialog = new MapDialogFragment();
        final Bundle bundle = new Bundle();
        bundle.putString(DIALOG_PREFIX, prefix);
        dialog.setArguments(bundle);
        return dialog;
    }

    private void setupQuery() {
        if (ListFragment.lameStatic.dbHelper != null) {
            final int cacheSize = MainActivity.getNetworkCache().size();
            if (cacheSize > (ListFragment.lameStatic.networkCache.maxSize() / 4)) {
                // don't load, there's already networks to show
                Logging.info("cacheSize: " + cacheSize + ", skipping previous networks");
                return;
            }

            final String sql = "SELECT bssid FROM "
                    + DatabaseHelper.LOCATION_TABLE + " ORDER BY _id DESC LIMIT ?";

            final PooledQueryExecutor.Request request = new PooledQueryExecutor.Request( sql,
                new String[]{(ListFragment.lameStatic.networkCache.maxSize() * 2)+""},
                new PooledQueryExecutor.ResultHandler() {
                @Override
                public boolean handleRow( final Cursor cursor ) {
                    final String bssid = cursor.getString(0);
                    final ConcurrentLinkedHashMap<String,Network> networkCache = MainActivity.getNetworkCache();

                    Network network = networkCache.get( bssid );
                    // MainActivity.info("RAW bssid: " + bssid);
                    if ( network == null ) {
                        network = ListFragment.lameStatic.dbHelper.getNetwork( bssid );
                        if ( network != null ) {
                            networkCache.put( network.getBssid(), network );

                            if (networkCache.isFull()) {
                                Logging.info("Cache is full, breaking out of query result handling");
                                return false;
                            }
                        }
                    }
                    return true;
                }

                @Override
                public void complete() {
                    if ( mapView != null ) {
                        // force a redraw
                        mapView.postInvalidate();
                    }
                }
            }, ListFragment.lameStatic.dbHelper);
            PooledQueryExecutor.enqueue(request);
        }
    }
    private static final PointExtractor<LatLng> latLngPointExtractor = new PointExtractor<LatLng>() {
        @Override
        public double getX(LatLng point) {
                return point.getLatitude() * 1000000;
        }

        @Override
        public double getY(LatLng point) {
                return point.getLongitude() * 1000000;
        }
    };

    public static int getRouteColorForMapType(final int mapType, final boolean nightMode) {
        if (nightMode) {
                return OVERLAY_LIGHT;
        } else if (mapType != MapTypes.MAP_TYPE_NORMAL && mapType != MapTypes.MAP_TYPE_TERRAIN
            && mapType != MapTypes.MAP_TYPE_NONE) {
            return OVERLAY_LIGHT;
        }
        return OVERLAY_DARK;
    }
}
