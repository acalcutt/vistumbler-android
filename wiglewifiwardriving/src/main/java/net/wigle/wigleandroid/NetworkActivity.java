package net.wigle.wigleandroid;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static net.wigle.wigleandroid.util.BluetoothUtil.BLE_SERVICE_CHARACTERISTIC_MAP;
import static net.wigle.wigleandroid.util.BluetoothUtil.BLE_STRING_CHARACTERISTIC_UUIDS;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.FragmentActivity;

import android.text.ClipboardManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.razir.progressbutton.DrawableButton;
import com.github.razir.progressbutton.DrawableButtonExtensionsKt;
import com.github.razir.progressbutton.ProgressButtonHolderKt;

import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;

import net.wigle.wigleandroid.background.KmlSurveyWriter;
import net.wigle.wigleandroid.background.PooledQueryExecutor;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.listener.WiFiScanUpdater;
import net.wigle.wigleandroid.model.ConcurrentLinkedHashMap;
import net.wigle.wigleandroid.model.MccMncRecord;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.model.OUI;
import net.wigle.wigleandroid.model.Observation;
import net.wigle.wigleandroid.ui.NetworkListUtil;
import net.wigle.wigleandroid.ui.ScreenChildActivity;
import net.wigle.wigleandroid.ui.ThemeUtil;
import net.wigle.wigleandroid.ui.WiGLEConfirmationDialog;
import net.wigle.wigleandroid.ui.WiGLEToast;
import net.wigle.wigleandroid.util.BluetoothUtil;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import kotlin.Unit;

@SuppressWarnings("deprecation")
public class NetworkActivity extends ScreenChildActivity implements DialogListener, WiFiScanUpdater {
    private static final int MENU_RETURN = 11;
    private static final int MENU_COPY = 12;
    private static final int NON_CRYPTO_DIALOG = 130;
    private static final int SITE_SURVEY_DIALOG = 131;


    private static final int MSG_OBS_UPDATE = 1;
    private static final int MSG_OBS_DONE = 2;
    private static final int MSG_FIRSTTIME = 3;

    private static final int DEFAULT_ZOOM = 18;

    private Network network;
    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private int observations = 0;
    private boolean isDbResult = false;
    private final ConcurrentLinkedHashMap<LatLng, Integer> obsMap = new ConcurrentLinkedHashMap<>(512);
    private final ConcurrentLinkedHashMap<LatLng, Observation> localObsMap = new ConcurrentLinkedHashMap<>(1024);
    private NumberFormat numberFormat;

    // used for shutting extraneous activities down on an error
    public static NetworkActivity networkActivity;

    /** Called when the activity is first created. */
    @SuppressLint("MissingPermission")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logging.info("NET: onCreate");
        super.onCreate(savedInstanceState);

        if (ListFragment.lameStatic.oui == null) {
            ListFragment.lameStatic.oui = new OUI(getAssets());
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        // set language
        numberFormat = NumberFormat.getNumberInstance(MainActivity.getLocale(this, this.getResources().getConfiguration()));
        if (numberFormat instanceof DecimalFormat) {
            numberFormat.setMinimumFractionDigits(0);
            numberFormat.setMaximumFractionDigits(2);
        }
        MainActivity.setLocale( this );
        setContentView(R.layout.network);
        networkActivity = this;

        EdgeToEdge.enable(this);
        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        ThemeUtil.setNavTheme(getWindow(), this, prefs);

        // Handle window insets for edge-to-edge display
        View networkMain = findViewById(R.id.network_main);
        View bottomTools = findViewById(R.id.bottom_tools_wrapper);
        if (null != networkMain) {
            ViewCompat.setOnApplyWindowInsetsListener(networkMain, (v, insets) -> {
                final Insets statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars() | 
                        WindowInsetsCompat.Type.displayCutout());
                final Insets navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                // Apply insets to root - combine nav bar left/right with status bar/cutout
                v.setPadding(
                        Math.max(statusBars.left, navBars.left),
                        statusBars.top,
                        Math.max(statusBars.right, navBars.right),
                        0);
                // Apply bottom insets to bottom_tools_wrapper for navigation bar
                if (bottomTools != null) {
                    bottomTools.setPadding(bottomTools.getPaddingLeft(), bottomTools.getPaddingTop(), 
                            bottomTools.getPaddingRight(), navBars.bottom);
                }
                return insets;
            });
        }

        final Intent intent = getIntent();
        final String bssid = intent.getStringExtra( ListFragment.NETWORK_EXTRA_BSSID );
        isDbResult = intent.getBooleanExtra(ListFragment.NETWORK_EXTRA_IS_DB_RESULT, false);
        Logging.info( "bssid: " + bssid + " isDbResult: " + isDbResult);

        if (null != MainActivity.getNetworkCache()) {
            network = MainActivity.getNetworkCache().get(bssid);
        }

        TextView tv = findViewById( R.id.bssid );
        tv.setText( bssid );
        tv.setOnLongClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (null != clipboard) {
                clipboard.setText(bssid);
            }
            return true;
        });

        if ( network == null ) {
            Logging.info( "no network found in cache for bssid: " + bssid );
        } else {
            // do gui work
            tv = findViewById( R.id.ssid );
            tv.setText( network.getSsid() );
            tv.setOnLongClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (null != clipboard) {
                    clipboard.setText(network.getSsid());
                }
                return true;
            });

            final String ouiString = network.getOui(ListFragment.lameStatic.oui);
            tv = findViewById( R.id.oui );
            tv.setText( ouiString );

            final int image = NetworkListUtil.getImage( network );
            final ImageView ico = findViewById( R.id.wepicon );
            ico.setImageResource( image );

            final ImageView btico = findViewById(R.id.bticon);
            if (NetworkType.BT.equals(network.getType()) || NetworkType.BLE.equals(network.getType())) {
                btico.setVisibility(VISIBLE);
                Integer btImageId = NetworkListUtil.getBtImage(network);
                if (null == btImageId) {
                    btico.setVisibility(GONE);
                } else {
                    btico.setImageResource(btImageId);
                }
            } else {
                btico.setVisibility(GONE);
            }

            tv = findViewById( R.id.na_signal );
            final int level = network.getLevel();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tv.setTextColor( NetworkListUtil.getTextColorForSignal(this, level));
            } else {
                tv.setTextColor( NetworkListUtil.getSignalColor( level, false) );
            }
            tv.setText( numberFormat.format( level ) );

            tv = findViewById( R.id.na_type );
            tv.setText( network.getType().name() );

            tv = findViewById( R.id.na_firsttime );
            tv.setText( NetworkListUtil.getTime(network, true, getApplicationContext()) );

            tv = findViewById( R.id.na_lasttime );
            tv.setText( NetworkListUtil.getTime(network, false, getApplicationContext()) );

            tv = findViewById( R.id.na_chan );
            Integer chan = network.getChannel();
            if ( NetworkType.WIFI.equals(network.getType()) ) {
                chan = chan != null ? chan : network.getFrequency();
                tv.setText(numberFormat.format(chan));
            } else if ( NetworkType.CDMA.equals(network.getType()) || chan == null) {
                tv.setText(getString(R.string.na));
            } else if (NetworkType.isBtType(network.getType())) {
                final String channelCode = NetworkType.channelCodeTypeForNetworkType(network.getType());
                tv.setText(String.format(MainActivity.getLocale(getApplicationContext(),
                                getApplicationContext().getResources().getConfiguration()),
                        "%s %d", (null == channelCode?"":channelCode), chan));
            } else {
                final String[] cellCapabilities = network.getCapabilities().split(";");
                tv.setText(String.format(MainActivity.getLocale(getApplicationContext(),
                        getApplicationContext().getResources().getConfiguration()),
                        "%s %s %d", cellCapabilities[0], NetworkType.channelCodeTypeForNetworkType(network.getType()), chan));
            }

            tv = findViewById( R.id.na_cap );
            tv.setText( network.getCapabilities().replace("][", "]  [") );

            final ImageView ppImg = findViewById(R.id.passpoint_logo_net);
            if (network.isPasspoint()) {
                ppImg.setVisibility(VISIBLE);
            } else {
                ppImg.setVisibility(GONE);
            }
            tv = findViewById( R.id.na_rcois );
            if (network.getRcois() != null) {
                tv.setText( network.getRcois() );
            }
            else {
                TextView row = findViewById(R.id.na_rcoi_label);
                row.setVisibility(View.INVISIBLE);
            }

            if ( NetworkType.isGsmLike(network.getType())) { // cell net types  with advanced data
                if ((bssid != null) && (bssid.length() > 5) && (bssid.indexOf('_') >= 5)) {
                    final String operatorCode = bssid.substring(0, bssid.indexOf("_"));

                    MccMncRecord rec = null;
                    if (operatorCode.length() == 6) {
                        final String mnc = operatorCode.substring(3);
                        final String mcc = operatorCode.substring(0, 3);

                        try {
                            final MainActivity.State s = MainActivity.getStaticState();
                            if (s != null && s.mxcDbHelper != null) {
                                rec = s.mxcDbHelper.networkRecordForMccMnc(mcc, mnc);
                            }
                        } catch (SQLException sqex) {
                            Logging.error("Unable to access Mxc Database: ",sqex);
                        }
                        if (rec != null) {
                            View v = findViewById(R.id.cell_info);
                            v.setVisibility(VISIBLE);
                            tv = findViewById( R.id.na_cell_status );
                            tv.setText(rec.getStatus() );
                            tv = findViewById( R.id.na_cell_brand );
                            tv.setText(rec.getBrand());
                            tv = findViewById( R.id.na_cell_bands );
                            tv.setText( rec.getBands());
                            if (rec.getNotes() != null && !rec.getNotes().isEmpty()) {
                                v = findViewById(R.id.cell_notes_row);
                                v.setVisibility(VISIBLE);
                                tv = findViewById( R.id.na_cell_notes );
                                tv.setText( rec.getNotes());
                            }
                        }
                    }
                } else {
                    Logging.warn("unable to get operatorCode for "+bssid);
                }
            }

            if (NetworkType.isBtType(network.getType())) {
                View v = findViewById(R.id.ble_info);
                v.setVisibility(VISIBLE);
                if (network.getBleMfgrId() != null || network.getBleMfgr() != null) {
                    v = findViewById(R.id.ble_vendor_row);
                    v.setVisibility(VISIBLE);
                    tv = findViewById( R.id.na_ble_vendor_id );
                    tv.setText((null != network.getBleMfgrId())?"0x"+String.format("%04X", network.getBleMfgrId()):"-");
                    tv = findViewById( R.id.na_ble_vendor_lookup );
                    tv.setText(network.getBleMfgr() );
                }

                List<String> serviceUuids = network.getBleServiceUuids();
                if (null != serviceUuids && !serviceUuids.isEmpty()) {
                    v = findViewById(R.id.ble_services_row);
                    v.setVisibility(VISIBLE);
                    tv = findViewById( R.id.na_ble_service_uuids );
                    tv.setText(serviceUuids.toString() );
                }
            }
            // kick off the query now that we have our map
            setupMap(network, savedInstanceState, prefs);
            setupButtons(network, prefs);
            if (NetworkType.BLE.equals(network.getType())) {
                setupBleInspection(this, network);
            } else {
                View bleToolsLayout = findViewById(R.id.ble_tools_row);
                if (bleToolsLayout != null) {
                    bleToolsLayout.setVisibility(GONE);
                }
            }
            setupQuery();
            setupFirstTimeQuery();
        }
    }

    @Override
    public void onDestroy() {
        Logging.info("NET: onDestroy");
        networkActivity = null;
        if (mapView != null) {
            mapView.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onResume() {
        Logging.info("NET: onResume");
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
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

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        Logging.info("NET: onSaveInstanceState");
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            try {
                mapView.onSaveInstanceState(outState);
            } catch (android.os.BadParcelableException bpe) {
                Logging.error("Exception saving NetworkActivity instance state: ", bpe);
            }
        }
    }

    @Override
    public void onLowMemory() {
        Logging.info("NET: onLowMemory");
        super.onLowMemory();
        if (mapView != null) {
            mapView.onLowMemory();
        }
    }

    @SuppressLint("HandlerLeak")
    private void setupQuery() {
        // what runs on the gui thread
        final Handler handler = new Handler() {
            @Override
            public void handleMessage( final Message msg ) {
                final TextView tv = findViewById( R.id.na_observe );
                if ( msg.what == MSG_OBS_UPDATE ) {
                    tv.setText( numberFormat.format( observations ));
                } else if ( msg.what == MSG_OBS_DONE ) {
                    tv.setText( numberFormat.format( observations ) );
                    // ALIBI: assumes all observations belong to one "cluster" w/ a single centroid.
                    final LatLng estCentroid = computeBasicLocation(obsMap);
                    final int zoomLevel = computeZoom(obsMap, estCentroid);
                    if (mapView != null && mapLibreMap != null) {
                        // Add observation markers to the map
                        int count = 0;
                        for (Map.Entry<LatLng, Integer> obs : obsMap.entrySet()) {
                            final LatLng latLon = obs.getKey();
                            final int level = obs.getValue();

                            // default to initial position
                            if (count == 0 && network.getLatLng() == null) {
                                final CameraPosition cameraPosition = new CameraPosition.Builder()
                                        .target(latLon).zoom(DEFAULT_ZOOM).build();
                                mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                            }

                            // Add observation as a feature in the GeoJSON source
                            count++;
                        }
                        // If we got a good centroid, center on it
                        if (estCentroid.getLatitude() != 0d && estCentroid.getLongitude() != 0d) {
                            final CameraPosition cameraPosition = new CameraPosition.Builder()
                                    .target(estCentroid).zoom(zoomLevel).build();
                            mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                        }
                        // Update the observation source with all points
                        updateObservationMarkers();
                        Logging.info("observation count: " + count);
                    }
                    if ( NetworkType.WIFI.equals(network.getType()) ) {
                        View v = findViewById(R.id.survey);
                        v.setVisibility(VISIBLE);
                    }
                }
            }
        };

        final String sql = "SELECT level,lat,lon FROM "
                + DatabaseHelper.LOCATION_TABLE + " WHERE bssid = ?" +
                " ORDER BY _id DESC limit ?" ;

        PooledQueryExecutor.enqueue( new PooledQueryExecutor.Request( sql,
                new String[]{network.getBssid(), obsMap.maxSize()+""}, new PooledQueryExecutor.ResultHandler() {
            @Override
            public boolean handleRow( final Cursor cursor ) {
                observations++;
                obsMap.put( new LatLng( cursor.getFloat(1), cursor.getFloat(2) ), cursor.getInt(0) );
                if ( ( observations % 10 ) == 0 ) {
                    // change things on the gui thread
                    handler.sendEmptyMessage( MSG_OBS_UPDATE );
                }
                return true;
            }

            @Override
            public void complete() {
                handler.sendEmptyMessage( MSG_OBS_DONE );
            }
        }, ListFragment.lameStatic.dbHelper ));
        //ListFragment.lameStatic.dbHelper.addToQueue( request );
    }

    @SuppressLint("HandlerLeak")
    private void setupFirstTimeQuery() {
        // Handler to update UI on the main thread
        final Handler handler = new Handler() {
            @Override
            public void handleMessage( final Message msg ) {
                if ( msg.what == MSG_FIRSTTIME && msg.obj != null) {
                    final TextView tv = findViewById( R.id.na_firsttime );
                    tv.setText( (String) msg.obj );
                }
            }
        };

        // Query MIN(time) from location table for this bssid, excluding invalid GPS times (0)
        final String sql = "SELECT MIN(time) FROM " + DatabaseHelper.LOCATION_TABLE + " WHERE bssid = ? AND time > 0";

        PooledQueryExecutor.enqueue( new PooledQueryExecutor.Request( sql,
                new String[]{network.getBssid()}, new PooledQueryExecutor.ResultHandler() {
            @Override
            public boolean handleRow( final Cursor cursor ) {
                if (!cursor.isNull(0)) {
                    long firstTime = cursor.getLong(0);
                    // Format the time
                    String formatted;
                    if (DateFormat.is24HourFormat(getApplicationContext())) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                        formatted = sdf.format(new Date(firstTime));
                    } else {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd h:mm:ss a", Locale.US);
                        formatted = sdf.format(new Date(firstTime));
                    }
                    Message msg = handler.obtainMessage(MSG_FIRSTTIME, formatted);
                    handler.sendMessage(msg);
                }
                return false; // only expect one result
            }

            @Override
            public void complete() {
                // nothing needed
            }
        }, ListFragment.lameStatic.dbHelper ));
    }

    private void setupMap(final Network network, final Bundle savedInstanceState, final SharedPreferences prefs) {
        // Initialize MapLibre
        MapLibre.getInstance(this);
        
        mapView = new MapView(this);
        try {
            mapView.onCreate(savedInstanceState);
        } catch (NullPointerException ex) {
            Logging.error("npe in mapView.onCreate: " + ex, ex);
        }

        mapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(@NonNull MapLibreMap map) {
                mapLibreMap = map;
                
                // Disable built-in attribution and logo
                mapLibreMap.getUiSettings().setAttributionEnabled(false);
                mapLibreMap.getUiSettings().setLogoEnabled(false);

                final String styleUrl = "https://tiles.wifidb.net/styles/WDB_OSM/style.json";
                mapLibreMap.setStyle(styleUrl, new Style.OnStyleLoaded() {
                    @Override
                    public void onStyleLoaded(@NonNull Style style) {
                        // Set initial camera position if network has location
                        if (network != null && network.getLatLng() != null) {
                            com.google.android.gms.maps.model.LatLng gmsLatLng = network.getLatLng();
                            LatLng latLng = new LatLng(gmsLatLng.latitude, gmsLatLng.longitude);
                            final CameraPosition cameraPosition = new CameraPosition.Builder()
                                    .target(latLng).zoom(DEFAULT_ZOOM).build();
                            mapLibreMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                        }

                        // Add GeoJSON source for observations
                        try {
                            GeoJsonSource obsSource = new GeoJsonSource("observations", 
                                    FeatureCollection.fromFeatures(new Feature[]{}));
                            style.addSource(obsSource);

                            // Add circle layer for observation points with color based on signal strength
                            CircleLayer obsLayer = new CircleLayer("observations_layer", "observations");
                            obsLayer.setProperties(
                                    PropertyFactory.circleRadius(8f),
                                    PropertyFactory.circleColor(
                                            Expression.interpolate(
                                                    Expression.linear(),
                                                    Expression.coalesce(Expression.get("signal"), Expression.literal(-100)),
                                                    Expression.stop(-100, Expression.rgb(255, 0, 0)),     // Red
                                                    Expression.stop(-80, Expression.rgb(255, 165, 0)),   // Orange
                                                    Expression.stop(-60, Expression.rgb(255, 255, 0)),   // Yellow
                                                    Expression.stop(-40, Expression.rgb(0, 255, 0))      // Green
                                            )
                                    ),
                                    PropertyFactory.circleStrokeWidth(2f),
                                    PropertyFactory.circleStrokeColor(Color.WHITE)
                            );
                            style.addLayer(obsLayer);

                            // Add centroid marker source and layer
                            GeoJsonSource centroidSource = new GeoJsonSource("centroid",
                                    FeatureCollection.fromFeatures(new Feature[]{}));
                            style.addSource(centroidSource);

                            CircleLayer centroidLayer = new CircleLayer("centroid_layer", "centroid");
                            centroidLayer.setProperties(
                                    PropertyFactory.circleRadius(12f),
                                    PropertyFactory.circleColor(Color.BLUE),
                                    PropertyFactory.circleStrokeWidth(3f),
                                    PropertyFactory.circleStrokeColor(Color.WHITE)
                            );
                            style.addLayer(centroidLayer);

                            // Add device marker source and layer - shows the selected network location
                            GeoJsonSource deviceSource = new GeoJsonSource("device",
                                    FeatureCollection.fromFeatures(new Feature[]{}));
                            style.addSource(deviceSource);

                            // Create a pin marker icon for the device location with color based on network type
                            int markerColor = getNetworkColor(network);
                            Bitmap markerBitmap = createMarkerBitmap(markerColor);
                            style.addImage("device-marker", markerBitmap);

                            // Device marker layer using a pin icon (distinct from signal circles)
                            SymbolLayer deviceLayer = new SymbolLayer("device_layer", "device");
                            deviceLayer.setProperties(
                                    PropertyFactory.iconImage("device-marker"),
                                    PropertyFactory.iconSize(1.0f),
                                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                                    PropertyFactory.iconAllowOverlap(true)
                            );
                            style.addLayer(deviceLayer);

                            // Add the device marker immediately if we have location
                            if (network != null && network.getLatLng() != null) {
                                com.google.android.gms.maps.model.LatLng gmsLatLng = network.getLatLng();
                                Feature deviceFeature = Feature.fromGeometry(
                                        Point.fromLngLat(gmsLatLng.longitude, gmsLatLng.latitude));
                                deviceSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{deviceFeature}));
                            }

                            // Add survey source and layer for real-time site survey observations
                            GeoJsonSource surveySource = new GeoJsonSource("survey",
                                    FeatureCollection.fromFeatures(new Feature[]{}));
                            style.addSource(surveySource);

                            // Survey layer with signal-based coloring (same as observations)
                            CircleLayer surveyLayer = new CircleLayer("survey_layer", "survey");
                            surveyLayer.setProperties(
                                    PropertyFactory.circleRadius(8f),
                                    PropertyFactory.circleColor(
                                            Expression.interpolate(
                                                    Expression.linear(),
                                                    Expression.coalesce(Expression.get("signal"), Expression.literal(-100)),
                                                    Expression.stop(-100, Expression.rgb(255, 0, 0)),     // Red
                                                    Expression.stop(-80, Expression.rgb(255, 165, 0)),   // Orange
                                                    Expression.stop(-60, Expression.rgb(255, 255, 0)),   // Yellow
                                                    Expression.stop(-40, Expression.rgb(0, 255, 0))      // Green
                                            )
                                    ),
                                    PropertyFactory.circleStrokeWidth(2f),
                                    PropertyFactory.circleStrokeColor(Color.WHITE)
                            );
                            style.addLayer(surveyLayer);
                        } catch (Exception ex) {
                            Logging.error("Failed to create observation sources/layers: " + ex.getMessage());
                        }
                    }
                });
            }
        });

        final RelativeLayout rlView = findViewById(R.id.netmap_rl);
        if (rlView != null) {
            rlView.addView(mapView);
        }
    }

    /**
     * Get the color for a network based on its type and encryption.
     * Colors match the live map layer colors.
     */
    private int getNetworkColor(Network network) {
        if (network == null) {
            return Color.GRAY;
        }

        NetworkType type = network.getType();
        if (type == null) {
            return Color.GRAY;
        }

        if (NetworkType.WIFI.equals(type)) {
            // WiFi colors based on encryption
            int crypto = network.getCrypto();
            switch (crypto) {
                case Network.CRYPTO_NONE:
                    return Color.parseColor("#4CAF50"); // Green - Open
                case Network.CRYPTO_WEP:
                    return Color.parseColor("#FF9800"); // Orange - WEP
                case Network.CRYPTO_WPA:
                case Network.CRYPTO_WPA2:
                case Network.CRYPTO_WPA3:
                default:
                    return Color.parseColor("#F44336"); // Red - Secure
            }
        } else if (NetworkType.isBtType(type)) {
            return Color.parseColor("#2196F3"); // Blue - Bluetooth
        } else if (NetworkType.isCellType(type)) {
            return Color.parseColor("#9C27B0"); // Purple - Cell
        }

        return Color.GRAY;
    }

    /**
     * Create a marker pin bitmap for the device location.
     * Creates a classic map pin shape with a colored fill and white border.
     * @param color The fill color for the marker based on network type
     */
    private Bitmap createMarkerBitmap(int color) {
        int width = 48;
        int height = 64;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Create the pin shape
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(color);
        fillPaint.setStyle(Paint.Style.FILL);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);

        // Draw a pin: circle top with pointed bottom
        Path pinPath = new Path();
        float centerX = width / 2f;
        float circleRadius = width / 2f - 4;
        float circleY = circleRadius + 2;

        // Draw the main circle (top of pin)
        canvas.drawCircle(centerX, circleY, circleRadius, fillPaint);
        canvas.drawCircle(centerX, circleY, circleRadius, strokePaint);

        // Draw the point (bottom of pin)
        pinPath.moveTo(centerX - circleRadius * 0.6f, circleY + circleRadius * 0.7f);
        pinPath.lineTo(centerX, height - 4);
        pinPath.lineTo(centerX + circleRadius * 0.6f, circleY + circleRadius * 0.7f);
        pinPath.close();
        canvas.drawPath(pinPath, fillPaint);
        canvas.drawPath(pinPath, strokePaint);

        // Draw inner white circle
        Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerPaint.setColor(Color.WHITE);
        innerPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX, circleY, circleRadius * 0.4f, innerPaint);

        return bitmap;
    }

    private void updateObservationMarkers() {
        if (mapLibreMap == null) return;
        Style style = mapLibreMap.getStyle();
        if (style == null) return;

        try {
            // Build features for all observations
            Feature[] features = new Feature[obsMap.size()];
            int i = 0;
            for (Map.Entry<LatLng, Integer> obs : obsMap.entrySet()) {
                LatLng pos = obs.getKey();
                int signal = obs.getValue();
                Feature f = Feature.fromGeometry(Point.fromLngLat(pos.getLongitude(), pos.getLatitude()));
                f.addNumberProperty("signal", signal);
                features[i++] = f;
            }

            // Update the observations source
            GeoJsonSource obsSource = style.getSourceAs("observations");
            if (obsSource != null) {
                obsSource.setGeoJson(FeatureCollection.fromFeatures(features));
            }

            // Update centroid marker
            LatLng centroid = computeBasicLocation(obsMap);
            if (centroid.getLatitude() != 0d && centroid.getLongitude() != 0d) {
                Feature centroidFeature = Feature.fromGeometry(
                        Point.fromLngLat(centroid.getLongitude(), centroid.getLatitude()));
                GeoJsonSource centroidSource = style.getSourceAs("centroid");
                if (centroidSource != null) {
                    centroidSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{centroidFeature}));
                }
            }
        } catch (Exception ex) {
            Logging.error("Error updating observation markers: " + ex.getMessage());
        }
    }

    private LatLng computeBasicLocation(ConcurrentLinkedHashMap<LatLng, Integer> obsMap) {
        double latSum = 0.0;
        double lonSum = 0.0;
        double weightSum = 0.0;
        for (Map.Entry<LatLng, Integer> obs : obsMap.entrySet()) {
            if (null != obs.getKey()) {
                float cleanSignal = cleanSignal((float) obs.getValue());
                final double latV = obs.getKey().getLatitude();
                final double lonV = obs.getKey().getLongitude();
                if (Math.abs(latV) > 0.01d && Math.abs(lonV) > 0.01d) { // 0 GPS-coord check
                    cleanSignal *= cleanSignal;
                    latSum += (obs.getKey().getLatitude() * cleanSignal);
                    lonSum += (obs.getKey().getLongitude() * cleanSignal);
                    weightSum += cleanSignal;
                }
            }
        }
        double trilateratedLatitude = 0;
        double trilateratedLongitude = 0;
        if (weightSum > 0) {
            trilateratedLatitude = latSum / weightSum;
            trilateratedLongitude = lonSum / weightSum;
        }
        return new LatLng(trilateratedLatitude, trilateratedLongitude);
    }

    private LatLng computeObservationLocation(ConcurrentLinkedHashMap<LatLng, Observation> obsMap) {
        double latSum = 0.0;
        double lonSum = 0.0;
        double weightSum = 0.0;
        for (Map.Entry<LatLng, Observation> obs : obsMap.entrySet()) {
            if (null != obs.getKey()) {
                float cleanSignal = cleanSignal((float) obs.getValue().getRssi());
                final double latV = obs.getKey().getLatitude();
                final double lonV = obs.getKey().getLongitude();
                if (Math.abs(latV) > 0.01d && Math.abs(lonV) > 0.01d) { // 0 GPS-coord check
                    cleanSignal *= cleanSignal;
                    latSum += (obs.getKey().getLatitude() * cleanSignal);
                    lonSum += (obs.getKey().getLongitude() * cleanSignal);
                    weightSum += cleanSignal;
                }
            }
        }
        double trilateratedLatitude = 0;
        double trilateratedLongitude = 0;
        if (weightSum > 0) {
            trilateratedLatitude = latSum / weightSum;
            trilateratedLongitude = lonSum / weightSum;
        }
        return new LatLng(trilateratedLatitude, trilateratedLongitude);
    }

    private int computeZoom(ConcurrentLinkedHashMap<LatLng, Integer> obsMap, final LatLng centroid) {
        float maxDist = 0f;
        for (Map.Entry<LatLng, Integer> obs : obsMap.entrySet()) {
            float[] res = new float[3];
            Location.distanceBetween(centroid.getLatitude(), centroid.getLongitude(), 
                    obs.getKey().getLatitude(), obs.getKey().getLongitude(), res);
            if (res[0] > maxDist) {
                maxDist = res[0];
            }
        }
        Logging.info("max dist: " + maxDist);
        if (maxDist < 135) {
            return 18;
        } else if (maxDist < 275) {
            return 17;
        } else if (maxDist < 550) {
            return 16;
        } else if (maxDist < 1100) {
            return 15;
        } else if (maxDist < 2250) {
            return 14;
        } else if (maxDist < 4500) {
            return 13;
        } else if (maxDist < 9000) {
            return 12;
        } else if (maxDist < 18000) {
            return 11;
        } else if (maxDist < 36000) {
            return 10;
        } else {
            return DEFAULT_ZOOM;
        }
    }

    /**
     * Optimistic signal weighting
     */
    public static float cleanSignal(Float signal) {
        float signalMemo = signal;
        if (signal == 0f) {
            return 100f;
        } else if (signal >= -200 && signal < 0) {
            signalMemo += 200f;
        } else if (signal <= 0 || signal > 200) {
            signalMemo = 100f;
        }
        if (signalMemo < 1f) {
            signalMemo = 1f;
        }
        return signalMemo;
    }

    private void setupBleInspection(Activity activity, final Network network) {
        View interrogateView = findViewById(R.id.ble_tools_row);
        if (interrogateView != null) {
            interrogateView.setVisibility(VISIBLE);
        }
        final AtomicBoolean done = new AtomicBoolean(false);
        final Button pair = findViewById(R.id.query_ble_network);
        final View charView = findViewById(R.id.ble_chars_row);
        final TextView charContents = findViewById(R.id.ble_chars_content);
        if (null != pair) {
            ProgressButtonHolderKt.bindProgressButton(this, pair);
            final AtomicBoolean found = new AtomicBoolean(false);
            Set<BluetoothGattCharacteristic> characteristicsToQuery = new HashSet<>();
            ConcurrentHashMap<String, String> characteristicResults = new ConcurrentHashMap<>();
            final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
                @SuppressLint("MissingPermission")
                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    super.onServicesDiscovered(gatt, status);
                    final StringBuffer displayMessage = new StringBuffer();
                    for (BluetoothGattService service: gatt.getServices()) {
                        final String serviceId = service.getUuid().toString().substring(4,8);
                        final String serviceTitle = MainActivity.getMainActivity()
                                .getBleService(serviceId.toUpperCase());
                        if (service.getUuid() != null) {
                            Map<UUID, String> currentMap = BLE_SERVICE_CHARACTERISTIC_MAP.get(service.getUuid());
                            if (currentMap != null) {
                                for (UUID key : currentMap.keySet()) {
                                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(key);
                                    if (null != characteristic) {
                                        //DEBUG: Logging.error("enqueueing: " + currentMap.get(key));
                                        characteristicsToQuery.add(characteristic);
                                    } else {
                                        Logging.info(currentMap.get(key) + " is null");
                                    }
                                }
                            } else {
                                Logging.debug("Unhandled service: " + serviceTitle + " (" + serviceId + ")");
                            }
                        }

                        if (null != serviceTitle) {
                            int lastServicePeriod = serviceTitle.lastIndexOf(".");
                            displayMessage.append(lastServicePeriod == -1
                                            ? serviceTitle : serviceTitle.substring(lastServicePeriod+1))
                                    .append(" (0x").append(serviceId.toUpperCase()).append(")\n");
                            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                                final String characteristicUuid = characteristic.getUuid().toString();
                                if (characteristicUuid.length() >= 8) {
                                    final String charId = characteristicUuid.substring(4, 8);
                                    String charTitle = MainActivity.getMainActivity()
                                            .getBleCharacteristic(charId);
                                    if (null != charTitle) {
                                        int lastCharPeriod = charTitle.lastIndexOf(".");
                                        displayMessage.append("\t").append(lastCharPeriod == -1
                                                        ? charTitle : charTitle.substring(lastCharPeriod+1))
                                                .append(" (0x").append(charId.toUpperCase()).append(")\n");
                                    }
                                }
                            }
                        }
                    }
                    if (!characteristicsToQuery.isEmpty()) {
                        BluetoothGattCharacteristic first = characteristicsToQuery.iterator().next();
                        characteristicsToQuery.remove(first);
                        gatt.readCharacteristic(first);
                    } else {
                        found.set(false);
                        done.set(true);
                    }
                    runOnUiThread(() -> WiGLEToast.showOverActivity(
                            activity, R.string.btloc_title, displayMessage.toString(), Toast.LENGTH_LONG)
                    );
                }

                @SuppressLint("MissingPermission")
                @Override
                public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
                    super.onCharacteristicRead(gatt, characteristic, value, status);
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getValue() != null) {
                        Integer titleResourceStringId = BLE_STRING_CHARACTERISTIC_UUIDS.get(characteristic.getUuid());
                        if (null != titleResourceStringId) {
                            // common case: this is a string value characteristic.
                            final String characteristicStringValue = new String(characteristic.getValue());
                            characteristicResults.put(getString(titleResourceStringId), characteristicStringValue);
                        } else if (UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb").equals(characteristic.getUuid())) {
                            //ALIBI: PnP value has a slightly complex decoding
                            final String pnpValue = getPnpValue(characteristic);
                            characteristicResults.put(getString(R.string.ble_pnp_title), pnpValue);
                        } else if (UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb").equals(characteristic.getUuid())) {
                            //ALIBI: this could be added to the stringCharacteristicUuids map, but we're leaving it its own case in case we want to update the network SSID value
                            final String name = new String(characteristic.getValue());
                            characteristicResults.put(getString(R.string.ble_name_title), name);
                            // NB: _could_ replace BT name with the discovered dev name here, but is that useful?
                            //if (null == network.getSsid() || network.getSsid().isBlank()) {
                            //    network.setSsid(name);
                            //}
                        } else if (UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb").equals(characteristic.getUuid())) {
                            final int level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                            characteristicResults.put("\uD83D\uDD0B", ""+level); //ALIBI: skipping language files since this is an emoji
                        } else if (UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb").equals(characteristic.getUuid())) {
                            //ALIBI: appearance value has a slightly complex decoding
                            byte[] charValue = characteristic.getValue();
                            int appearanceValue = BluetoothUtil.getGattUint16(charValue);
                            int category = (appearanceValue >> 6) & 0xFF;
                            int subcategory = appearanceValue & 0x3F;
                            final String categoryHex = Integer.toHexString(category);
                            final String subcategoryHex = Integer.toHexString(subcategory);
                            final String appearanceString = MainActivity.getMainActivity().getBleAppearance(category, subcategory);
                            //DEBUG: Logging.info("APPEARANCE: " + categoryHex + ":" + subcategoryHex + " - " + appearanceString + " from "+ Hex.bytesToStringLowercase(characteristic.getValue()) + ": "+Integer.toHexString(appearanceValue));
                            characteristicResults.put(getString(R.string.ble_appearance_title), appearanceString + "( 0x" + categoryHex + ": 0x" + subcategoryHex + ")");
                        } else {
                            //TODO: heart rate will land here for now
                            Logging.info(characteristic.getUuid().toString()+": "+new String(characteristic.getValue()) );
                        }

                        if (!characteristicsToQuery.isEmpty()) {
                            BluetoothGattCharacteristic next = characteristicsToQuery.iterator().next();
                            characteristicsToQuery.remove(next);
                            gatt.readCharacteristic(next);
                        } else {
                            gatt.disconnect();
                            gatt.close();
                            if (characteristicResults.isEmpty()) {
                                runOnUiThread(() -> {
                                    WiGLEToast.showOverActivity(activity, R.string.btloc_title, "No results read", Toast.LENGTH_LONG);
                                    hideProgressCenter(pair);
                                });
                            } else {
                                final String results = characteristicDisplayString(characteristicResults);
                                runOnUiThread(() -> {
                                    charView.setVisibility(VISIBLE);
                                    charContents.setText(results);
                                    hideProgressCenter(pair);
                                });
                            }
                        }
                    }
                }

                @SuppressLint("MissingPermission")
                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic) {
                    super.onCharacteristicChanged(gatt, characteristic);
                    Logging.info("CHARACTERISTIC CHANGED" + characteristic.getUuid().toString()+" :"+new String(characteristic.getValue()) );
                    gatt.disconnect();
                    gatt.close();
                    found.set(false);
                    runOnUiThread(() -> WiGLEToast.showOverActivity(activity, R.string.btloc_title, "characteristic change."));
                }
                @SuppressLint("MissingPermission")
                @Override
                public void onServiceChanged(@NonNull BluetoothGatt gatt) {
                    super.onServiceChanged(gatt);
                    //DEBUG: Logging.info(gatt + " d!");
                    gatt.disconnect();
                    gatt.close();
                    found.set(false);
                    done.set(true);
                    runOnUiThread(() -> WiGLEToast.showOverActivity(activity, R.string.btloc_title, "service change."));
                }

                @SuppressLint("MissingPermission")
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices();
                        runOnUiThread(() -> WiGLEToast.showOverActivity(activity, R.string.btloc_title, "connected to device."));
                    } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                        Logging.info("Connecting...");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                        Logging.info("Disconnecting...");
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gatt.disconnect();
                        gatt.close();
                        found.set(false);
                        done.set(true);
                        runOnUiThread(() -> {
                            WiGLEToast.showOverActivity(activity, R.string.btloc_title, "disconnected from device.");
                            hideProgressCenter(pair);
                            if (!characteristicsToQuery.isEmpty() && !characteristicResults.isEmpty()) {
                                //ALIBI: we were interrupted, but have some characteristics to show
                                final String results = characteristicDisplayString(characteristicResults);
                                runOnUiThread(() -> {
                                    charView.setVisibility(VISIBLE);
                                    charContents.setText(results);
                                    hideProgressCenter(pair);
                                });
                            }
                        });
                    } else {
                        Logging.info("GATT Characteristic status: " + status + " new: " + newState);
                    }
                }
            };

            final BluetoothAdapter.LeScanCallback scanCallback = getLeScanCallback(network, found, done, gattCallback);

            pair.setOnClickListener(buttonView -> {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter != null) {
                        done.set(false);
                        found.set(false);
                        charView.setVisibility(GONE);
                        showProgressCenter(pair);
                        bluetoothAdapter.startLeScan(scanCallback); //TODO: should already be going on
                    }
                }
            });
        }
    }

    @NonNull
    private static String getPnpValue(@NonNull BluetoothGattCharacteristic characteristic) {
        byte[] charValue = characteristic.getValue();
        final String vendorIdSrc = BluetoothUtil.getGattUint8(charValue[0]) == 1 ? "BLE" : "USB";
        final String vendorId = ""+BluetoothUtil.getGattUint8(charValue[1]);
        final String productString = ""+BluetoothUtil.getGattUint8(charValue[2]);
        final String productVersionString = "" + BluetoothUtil.getGattUint8(charValue[3]);
        return vendorIdSrc + ":" +  vendorId + ":" + productString + ":" + productVersionString;
    }

    private BluetoothAdapter.LeScanCallback getLeScanCallback(Network network, AtomicBoolean found, AtomicBoolean done, BluetoothGattCallback gattCallback) {
        final ScanCallback leScanCallback = new ScanCallback() {
            @SuppressLint("MissingPermission")
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                if (!done.get()) {
                    if (null != result) {
                        final BluetoothDevice device = result.getDevice();
                        if (device.getAddress().compareToIgnoreCase(network.getBssid()) == 0) {
                            if (found.compareAndSet(false, true)) {
                                //DEBUG: Logging.info("** MATCHED DEVICE IN NetworkActivity: " + network.getBssid() + " **");
                                final BluetoothGatt btGatt = device.connectGatt(getApplicationContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                            }
                        }
                    }
                }
            }

            @Override
            @SuppressLint("MissingPermission")
            public void onBatchScanResults(List<ScanResult> results) {
                super.onBatchScanResults(results);
                if (!done.get()) {
                    if (results != null) {
                        for (ScanResult result : results) {
                            final BluetoothDevice device = result.getDevice();
                            if (device.getAddress().compareToIgnoreCase(network.getBssid()) == 0) {
                                if (found.compareAndSet(false, true)) {
                                    //DEBUG: Logging.info("** MATCHED DEVICE IN NetworkActivity: " + network.getBssid() + " **");
                                    final BluetoothGatt btGatt = device.connectGatt(getApplicationContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                                    btGatt.discoverServices();
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                Logging.info("LE failed before scan stop");
            }
        };


        return new BluetoothAdapter.LeScanCallback() {
            @SuppressLint("MissingPermission")
            @Override
            public void onLeScan(BluetoothDevice bluetoothDevice, int i, byte[] bytes) {
                if (!done.get()) {
                    if (bluetoothDevice.getAddress().compareToIgnoreCase(network.getBssid()) == 0) {
                        if (found.compareAndSet(false, true)) {
                            //DEBUG: Logging.info("** MATCHED DEVICE IN NetworkActivity: " + network.getBssid() + " **");
                            final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                            if (bluetoothAdapter != null) {
                                bluetoothAdapter.getBluetoothLeScanner().stopScan(leScanCallback);
                                bluetoothAdapter.getBluetoothLeScanner().flushPendingScanResults(leScanCallback);
                            }
                            final BluetoothGatt btGatt = bluetoothDevice.connectGatt(getApplicationContext(), false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                            //Logging.info("class: " + bluetoothDevice.getBluetoothClass().getMajorDeviceClass() + " (all " + bluetoothDevice.getBluetoothClass().getDeviceClass() + ") vs "+network.getCapabilities());
                            btGatt.discoverServices();
                        }
                    }
                }
            }
        };
    }

    private void setupButtons( final Network network, final SharedPreferences prefs ) {
        final ArrayList<String> hideAddresses = addressListForPref(prefs, PreferenceKeys.PREF_EXCLUDE_DISPLAY_ADDRS);
        final ArrayList<String> blockAddresses = addressListForPref(prefs, PreferenceKeys.PREF_EXCLUDE_LOG_ADDRS);
        final ArrayList<String> alertAddresses = addressListForPref(prefs, PreferenceKeys.PREF_ALERT_ADDRS);

        if ( ! NetworkType.WIFI.equals(network.getType()) && !NetworkType.isBtType(network.getType())) {
            final View filterRowView = findViewById(R.id.filter_row);
            filterRowView.setVisibility(GONE);
        } else {
            final CheckBox hideMacBox = findViewById( R.id.hide_mac );
            final CheckBox hideOuiBox = findViewById( R.id.hide_oui );
            final CheckBox disableLogMacBox = findViewById( R.id.block_mac );
            final CheckBox disableLogOuiBox = findViewById( R.id.block_oui );
            final CheckBox alertMacBox = findViewById( R.id.alert_mac );
            final CheckBox alertOuiBox = findViewById( R.id.alert_oui );


            if ( (null == network.getBssid()) || (network.getBssid().length() < 17)) {
                hideMacBox.setEnabled(false);
                disableLogMacBox.setEnabled(false);
                alertMacBox.setEnabled(false);
            } else {
                if (hideAddresses.contains(network.getBssid().toUpperCase(Locale.ROOT)) ) {
                    hideMacBox.setChecked(true);
                }
                if (blockAddresses.contains(network.getBssid().toUpperCase(Locale.ROOT))) {
                    disableLogMacBox.setChecked(true);
                }
                if  (alertAddresses.contains(network.getBssid().toUpperCase(Locale.ROOT))) {
                    alertMacBox.setChecked(true);
                }
            }

            if ( (null == network.getBssid()) || (network.getBssid().length() < 8) ) {
                hideOuiBox.setEnabled(false);
                disableLogOuiBox.setEnabled(false);
                alertOuiBox.setEnabled(false);
            } else {
                final String ouiString = network.getBssid().toUpperCase(Locale.ROOT).substring(0, 8);
                if (hideAddresses.contains(ouiString)) {
                    hideOuiBox.setChecked(true);
                }
                if (blockAddresses.contains(ouiString)) {
                    disableLogOuiBox.setChecked(true);
                }
                if (alertAddresses.contains(ouiString)) {
                    alertOuiBox.setChecked(true);
                }
            }

            hideMacBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(checked, network.getBssid(), false, hideAddresses,
                    PreferenceKeys.PREF_EXCLUDE_DISPLAY_ADDRS, prefs));

            hideOuiBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(
                    checked, network.getBssid(), true, hideAddresses,
                    PreferenceKeys.PREF_EXCLUDE_DISPLAY_ADDRS, prefs));

            disableLogMacBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(
                    checked, network.getBssid(), false, blockAddresses,
                    PreferenceKeys.PREF_EXCLUDE_LOG_ADDRS, prefs));

            disableLogOuiBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(
                    checked, network.getBssid(), true, blockAddresses,
                    PreferenceKeys.PREF_EXCLUDE_LOG_ADDRS, prefs));

            alertMacBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(
                    checked, network.getBssid(), false, alertAddresses,
                    PreferenceKeys.PREF_ALERT_ADDRS, prefs));

            alertOuiBox.setOnCheckedChangeListener((compoundButton, checked) -> checkChangeHandler(
                    checked, network.getBssid(), true, alertAddresses,
                    PreferenceKeys.PREF_ALERT_ADDRS, prefs));

            final Button startSurveyButton = findViewById(R.id.start_survey);
            final Button endSurveyButton = findViewById(R.id.end_survey);
            MainActivity.State state = MainActivity.getStaticState();
            startSurveyButton.setOnClickListener(buttonView -> {
                final FragmentActivity fa = this;
                //TODO: disabled until obsMap DB load complete?
                if (null != fa) {
                    final String message = String.format(getString(R.string.confirm_survey),
                            getString(R.string.end_survey), getString(R.string.nonstop));
                    WiGLEConfirmationDialog.createConfirmation(fa, message,
                            R.id.nav_data, SITE_SURVEY_DIALOG);
                }
            });
            endSurveyButton.setOnClickListener(buttonView -> {
                startSurveyButton.setVisibility(VISIBLE);
                endSurveyButton.setVisibility(GONE);
                if (null != state) {
                    state.wifiReceiver.unregisterWiFiScanUpdater();
                    // Show the hidden layers again after survey ends
                    if (mapLibreMap != null) {
                        Style style = mapLibreMap.getStyle();
                        if (style != null) {
                            // Show device marker
                            SymbolLayer deviceLayer = style.getLayerAs("device_layer");
                            if (deviceLayer != null) {
                                deviceLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE));
                            }
                            // Show observations layer
                            CircleLayer obsLayer = style.getLayerAs("observations_layer");
                            if (obsLayer != null) {
                                obsLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE));
                            }
                            // Show centroid layer
                            CircleLayer centroidLayer = style.getLayerAs("centroid_layer");
                            if (centroidLayer != null) {
                                centroidLayer.setProperties(PropertyFactory.visibility(Property.VISIBLE));
                            }
                        }
                    }
                    try {
                        KmlSurveyWriter kmlWriter = new KmlSurveyWriter(MainActivity.getMainActivity(), ListFragment.lameStatic.dbHelper,
                                "KmlSurveyWriter", true, network.getBssid(), localObsMap.values());
                        kmlWriter.start();
                    } catch (IllegalArgumentException e) {
                        Logging.error("Failed to start KML writer: ", e);
                    }
                    //TODO: do we want the obsMap back?
                }
            });

        }
    }

    private ArrayList<String> addressListForPref(final SharedPreferences prefs, final String key) {
        Gson gson = new Gson();
        String[] values = gson.fromJson(prefs.getString(key, "[]"), String[].class);
        return new ArrayList<>(Arrays.asList(values));
    }

    @SuppressLint("MissingPermission")
    private int getExistingSsid(final String ssid ) {
        final WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        final String quotedSsid = "\"" + ssid + "\"";
        int netId = -2;

        for ( final WifiConfiguration config : wifiManager.getConfiguredNetworks() ) {
            Logging.info( "bssid: " + config.BSSID
                            + " ssid: " + config.SSID
                            + " status: " + config.status
                            + " id: " + config.networkId
                            + " preSharedKey: " + config.preSharedKey
                            + " priority: " + config.priority
                            + " wepTxKeyIndex: " + config.wepTxKeyIndex
                            + " allowedAuthAlgorithms: " + config.allowedAuthAlgorithms
                            + " allowedGroupCiphers: " + config.allowedGroupCiphers
                            + " allowedKeyManagement: " + config.allowedKeyManagement
                            + " allowedPairwiseCiphers: " + config.allowedPairwiseCiphers
                            + " allowedProtocols: " + config.allowedProtocols
                            + " hiddenSSID: " + config.hiddenSSID
                            + " wepKeys: " + Arrays.toString( config.wepKeys )
            );
            if ( quotedSsid.equals( config.SSID ) ) {
                netId = config.networkId;
                break;
            }
        }

        return netId;
    }

    @Override
    public void handleDialog(final int dialogId) {
        switch(dialogId) {
            case NON_CRYPTO_DIALOG:
                connectToNetwork( null );
                break;
            case SITE_SURVEY_DIALOG:
                MainActivity.State state = MainActivity.getStaticState();
                final Button startSurveyButton = findViewById(R.id.start_survey);
                final Button endSurveyButton = findViewById(R.id.end_survey);
                if (null != state) {
                    startSurveyButton.setVisibility(GONE);
                    endSurveyButton.setVisibility(VISIBLE);
                    obsMap.clear();
                    localObsMap.clear();
                    // Clear the survey layer on the map and hide other layers
                    if (mapLibreMap != null) {
                        Style style = mapLibreMap.getStyle();
                        if (style != null) {
                            // Clear survey source
                            GeoJsonSource surveySource = style.getSourceAs("survey");
                            if (surveySource != null) {
                                surveySource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{}));
                            }
                            // Clear and hide observations layer
                            GeoJsonSource obsSource = style.getSourceAs("observations");
                            if (obsSource != null) {
                                obsSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{}));
                            }
                            CircleLayer obsLayer = style.getLayerAs("observations_layer");
                            if (obsLayer != null) {
                                obsLayer.setProperties(PropertyFactory.visibility(Property.NONE));
                            }
                            // Hide the centroid layer
                            CircleLayer centroidLayer = style.getLayerAs("centroid_layer");
                            if (centroidLayer != null) {
                                centroidLayer.setProperties(PropertyFactory.visibility(Property.NONE));
                            }
                            // Hide the device marker during survey
                            SymbolLayer deviceLayer = style.getLayerAs("device_layer");
                            if (deviceLayer != null) {
                                deviceLayer.setProperties(PropertyFactory.visibility(Property.NONE));
                            }
                        }
                    }
                    final String[] currentList = new String[]{network.getBssid()};
                    final Set<String> registerSet = new HashSet<>(Arrays.asList(currentList));
                    state.wifiReceiver.registerWiFiScanUpdater(this, registerSet);
                }
                break;
            default:
                Logging.warn("Network unhandled dialogId: " + dialogId);
        }
    }

    private void connectToNetwork( final String password ) {
        final int preExistingNetId = getExistingSsid( network.getSsid() );
        final WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService( Context.WIFI_SERVICE );
        int netId = -2;
        if ( preExistingNetId < 0 ) {
            final WifiConfiguration newConfig = new WifiConfiguration();
            newConfig.SSID = "\"" + network.getSsid() + "\"";
            newConfig.hiddenSSID = false;
            if ( password != null ) {
                if ( Network.CRYPTO_WEP == network.getCrypto() ) {
                    newConfig.wepKeys = new String[]{ "\"" + password + "\"" };
                }
                else {
                    newConfig.preSharedKey = "\"" + password + "\"";
                }
            }

            netId = wifiManager.addNetwork( newConfig );
        }

        if ( netId >= 0 ) {
            final boolean disableOthers = true;
            wifiManager.enableNetwork(netId, disableOthers);
        }
    }

    @Override
    public void handleWiFiSeen(String bssid, Integer rssi, Location location) {
        LatLng latest = new LatLng(location.getLatitude(), location.getLongitude());
        localObsMap.put(latest, new Observation(rssi, location.getLatitude(), location.getLongitude(), location.getAltitude()));

        // Update the map with the new survey observation (must run on UI thread)
        runOnUiThread(() -> {
            if (mapLibreMap != null) {
                Style style = mapLibreMap.getStyle();
                if (style != null) {
                    try {
                        // Build features for all survey observations
                        Feature[] features = new Feature[localObsMap.size()];
                        int i = 0;
                        for (Map.Entry<LatLng, Observation> obs : localObsMap.entrySet()) {
                            LatLng pos = obs.getKey();
                            int signal = obs.getValue().getRssi();
                            Feature f = Feature.fromGeometry(Point.fromLngLat(pos.getLongitude(), pos.getLatitude()));
                            f.addNumberProperty("signal", signal);
                            features[i++] = f;
                        }

                        // Update the survey source
                        GeoJsonSource surveySource = style.getSourceAs("survey");
                        if (surveySource != null) {
                            surveySource.setGeoJson(FeatureCollection.fromFeatures(features));
                        }

                        // Compute and update centroid based on survey observations
                        LatLng estCentroid = computeObservationLocation(localObsMap);
                        if (estCentroid.getLatitude() != 0d && estCentroid.getLongitude() != 0d) {
                            Feature centroidFeature = Feature.fromGeometry(
                                    Point.fromLngLat(estCentroid.getLongitude(), estCentroid.getLatitude()));
                            GeoJsonSource centroidSource = style.getSourceAs("centroid");
                            if (centroidSource != null) {
                                centroidSource.setGeoJson(FeatureCollection.fromFeatures(new Feature[]{centroidFeature}));
                            }
                        }

                        Logging.info("survey observation added: " + rssi + " dBm");
                    } catch (Exception ex) {
                        Logging.error("Error updating survey markers: " + ex.getMessage());
                    }
                }
            }
        });
    }

    public static class CryptoDialog extends DialogFragment {
        public static CryptoDialog newInstance(final Network network) {
            final CryptoDialog frag = new CryptoDialog();
            Bundle args = new Bundle();
            args.putString("ssid", network.getSsid());
            args.putString("capabilities", network.getCapabilities());
            args.putString("level", Integer.toString(network.getLevel()));
            frag.setArguments(args);
            return frag;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {

            final Dialog dialog = getDialog();
            View view = inflater.inflate(R.layout.cryptodialog, container);
            final Bundle args = getArguments();
            if (null != dialog) {
                dialog.setTitle(args != null ? args.getString("ssid") : "");
            }

            TextView text = view.findViewById( R.id.security );
            text.setText(args != null?args.getString("capabilities"):"");

            text = view.findViewById( R.id.signal );
            text.setText(args != null?args.getString("level"):"");

            final Button ok = view.findViewById( R.id.ok_button );

            final TextInputEditText password = view.findViewById( R.id.edit_password );
            password.addTextChangedListener( new SettingsFragment.SetWatcher() {
                @Override
                public void onTextChanged( final String s ) {
                    if (!s.isEmpty()) {
                        ok.setEnabled(true);
                    }
                }
            });

            final CheckBox showpass = view.findViewById( R.id.showpass );
            showpass.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if ( isChecked ) {
                    password.setInputType( InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD );
                    password.setTransformationMethod( null );
                }
                else {
                    password.setInputType( InputType.TYPE_TEXT_VARIATION_PASSWORD );
                    password.setTransformationMethod(
                            android.text.method.PasswordTransformationMethod.getInstance() );
                }
            });

            ok.setOnClickListener(buttonView -> {
                try {
                    final NetworkActivity networkActivity = (NetworkActivity) getActivity();
                    if (networkActivity != null && password.getText() != null) {
                        networkActivity.connectToNetwork(password.getText().toString());
                    }
                    if (null != dialog) {
                        dialog.dismiss();
                    }
                }
                catch ( Exception ex ) {
                    // guess it wasn't there anyways
                    Logging.info( "exception dismissing crypto dialog: " + ex );
                }
            });

            Button cancel = view.findViewById( R.id.cancel_button );
            cancel.setOnClickListener(buttonView -> {
                try {
                    if (null != dialog) {
                        dialog.dismiss();
                    }
                }
                catch ( Exception ex ) {
                    // guess it wasn't there anyways
                    Logging.info( "exception dismissing crypto dialog: " + ex );
                }
            });

            return view;
        }
    }

    /* Creates the menu items */
    @Override
    public boolean onCreateOptionsMenu( final Menu menu ) {
        MenuItem item = menu.add(0, MENU_COPY, 0, getString(R.string.menu_copy_network));
        item.setIcon( android.R.drawable.ic_menu_save );

        item = menu.add(0, MENU_RETURN, 0, getString(R.string.menu_return));
        item.setIcon( android.R.drawable.ic_menu_revert );

        return true;
    }

    /* Handles item selections */
    @Override
    public boolean onOptionsItemSelected( final MenuItem item ) {
        switch ( item.getItemId() ) {
            case MENU_RETURN:
                // call over to finish
                finish();
                return true;
            case MENU_COPY:
                // copy the netid
                if (network != null) {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setText(network.getBssid());
                }
                return true;
            case android.R.id.home:
                // MainActivity.info("NETWORK: actionbar back");
                if (isDbResult) {
                    // don't go back to main activity
                    finish();
                    return true;
                }
        }
        return false;
    }

    private void showProgressCenter(final Button button) {
        DrawableButtonExtensionsKt.showProgress(button, progressParams -> {
            progressParams.setProgressColor(Color.WHITE);
            progressParams.setGravity(DrawableButton.GRAVITY_CENTER);
            return Unit.INSTANCE;
        });
        button.setEnabled(false);
    }

    private void hideProgressCenter(final Button button) {
        button.setEnabled(true);
        DrawableButtonExtensionsKt.hideProgress(button, R.string.interrogate_ble);
    }

    private static String characteristicDisplayString(final Map<String, String> characteristicResults) {
        final StringBuilder results = new StringBuilder();
        boolean first = true;
        for (String key: characteristicResults.keySet()) {
            if (first) {
                first = false;
            } else {
                results.append("\n");
            }
            results.append(key).append(": ").append(characteristicResults.get(key));
        }
        return results.toString();
    }

    private static void checkChangeHandler(final boolean checked, final String bssid, final boolean ouiMode,
                                            final List<String> currentAddresses, String prefKey, SharedPreferences prefs) {
        if (bssid != null) {
            final String useBssid = ouiMode ? bssid.substring(0,8).toUpperCase(Locale.ROOT) : bssid.toUpperCase(Locale.ROOT);
            final String entryText = useBssid.replace(":", "");
            if (checked) {
                MacFilterActivity.addEntry(currentAddresses,
                        prefs, entryText, prefKey, true);
            } else {
                if (currentAddresses.remove(useBssid)) {
                    MacFilterActivity.updateEntries(currentAddresses,
                            prefs, prefKey);
                } else {
                    Logging.error("Attempted to remove " + prefKey + ": " + useBssid + " but unable to match. (oui: "+ouiMode+", "+currentAddresses+")");
                }
            }
        } else {
            Logging.error("null BSSID value in checkChangeHandler - unable to modify.");
        }
    }
}
