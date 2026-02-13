package net.wigle.wigleandroid;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import org.json.JSONObject;

import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * fetch wigle authentication tokens by scanning a barcode
 * @author rksh
 */
public class ActivateActivity extends AppCompatActivity {

    //intent string
    public static final String BARCODE_INTENT_SCHEME = "net.wigle.wigleandroid";
    public static final String BARCODE_INTENT_HOST = "activate";


    //log tag for activity
    private static final String LOG_TAG = "wigle.activate";

    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;

    private PreviewView cameraView;

    private static final int REQUEST_CAMERA = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activate);

        EdgeToEdge.enable(this);
        View backButtonWrapper = findViewById(R.id.activate_back_layout);
        if (null != backButtonWrapper) {
            ViewCompat.setOnApplyWindowInsetsListener(backButtonWrapper, new OnApplyWindowInsetsListener() {
                        @Override
                        public @org.jspecify.annotations.NonNull
                        WindowInsetsCompat onApplyWindowInsets(@org.jspecify.annotations.NonNull View v,
                                                               @org.jspecify.annotations.NonNull WindowInsetsCompat insets) {
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

        ImageButton backButton = findViewById(R.id.activate_back_button);
        if (null != backButton) {
            backButton.setOnClickListener(v -> finish());
        }

        launchBarcodeScanning();
    }

    private void launchBarcodeScanning() {
        cameraView = findViewById(R.id.camera_view);
        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_QR_CODE)
                        .build();
        barcodeScanner = BarcodeScanning.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (null != barcodeScanner) {
            barcodeScanner.close();
        }
        if (null != cameraExecutor) {
            cameraExecutor.shutdown();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Logging.error("Failed to start camera: ",e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder().setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
            @OptIn(markerClass = ExperimentalGetImage.class)
            @Override
            public void analyze(@NonNull ImageProxy image) {
                Image mediaImage = image.getImage();
                if (mediaImage == null) {
                    try { image.close(); } catch (Exception ignored) {}
                    return;
                }

                InputImage inputImage = InputImage.fromMediaImage(mediaImage, image.getImageInfo().getRotationDegrees());

                barcodeScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        if (barcodes == null || barcodes.isEmpty()) {
                            return;
                        }
                        Logging.info("received detections");
                        for (Barcode qr : barcodes) {
                            if (qr == null) continue;
                            final String dv = qr.getDisplayValue();
                            if (dv == null) continue;

                            // existing WiGLE activation format: username:authname:token
                            if (dv.matches("^.*:[a-zA-Z0-9]*:[a-zA-Z0-9]*$")) {
                                String[] tokens = dv.split(":");
                                final SharedPreferences prefs = MainActivity.getMainActivity().getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                                final SharedPreferences.Editor editor = prefs.edit();
                                editor.putString(PreferenceKeys.PREF_USERNAME, tokens[0]);
                                editor.putString(PreferenceKeys.PREF_AUTHNAME, tokens[1]);
                                editor.putBoolean(PreferenceKeys.PREF_BE_ANONYMOUS, false);
                                editor.apply();
                                TokenAccess.setApiToken(prefs, tokens[2]);
                                MainActivity.refreshApiManager();
                                try { image.close(); } catch (Exception ignored) {}
                                finish();
                                return;
                            }

                            // WifiDB one-time redeem URL, e.g. https://<host>/wifidb/cp/redeem_link.php?token=...
                            if (dv.contains("redeem_link.php?token=")) {
                                final String redeemUrl = dv.trim();
                                Logging.info("Attempting to redeem WifiDB URL: " + redeemUrl);
                                new Thread(() -> {
                                    try {
                                        URL url = new URL(redeemUrl);
                                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                        conn.setRequestMethod("GET");
                                        conn.setConnectTimeout(5000);
                                        conn.setReadTimeout(5000);
                                        int rc = conn.getResponseCode();
                                        Logging.info("WifiDB redeem response code: " + rc);
                                        if (rc == 200) {
                                            InputStream is = conn.getInputStream();
                                            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                                            StringBuilder sb = new StringBuilder();
                                            String line;
                                            while ((line = rd.readLine()) != null) sb.append(line);
                                            rd.close();
                                            String resp = sb.toString();
                                            Logging.info("WifiDB redeem response body: " + resp);
                                            try {
                                                JSONObject obj = new JSONObject(resp);
                                                final String apikey = obj.optString("apikey", null);
                                                final String username = obj.optString("username", null);
                                                if (apikey != null && !apikey.isEmpty()) {
                                                    runOnUiThread(() -> {
                                                        final SharedPreferences prefs = MainActivity.getMainActivity().getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                                                        final SharedPreferences.Editor editor = prefs.edit();
                                                        if (username != null) editor.putString(PreferenceKeys.PREF_USERNAME, username);
                                                        editor.putBoolean(PreferenceKeys.PREF_BE_ANONYMOUS, false);
                                                        editor.apply();
                                                        TokenAccess.setApiToken(prefs, apikey);
                                                        MainActivity.refreshApiManager();
                                                        Toast.makeText(getApplicationContext(), "Activation successful!", Toast.LENGTH_SHORT).show();
                                                        try { image.close(); } catch (Exception ignored) {}
                                                        finish();
                                                    });
                                                } else {
                                                    Logging.error("apikey not found in WifiDB response");
                                                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), "API Key not found in response", Toast.LENGTH_SHORT).show());
                                                }
                                            } catch (Exception je) {
                                                Logging.error("Failed to parse redeem JSON: ", je);
                                                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Failed to parse server response", Toast.LENGTH_SHORT).show());
                                            }
                                        } else {
                                            Logging.warn("Redeem URL returned rc=" + rc);
                                            runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Server returned error: " + rc, Toast.LENGTH_SHORT).show());
                                        }
                                    } catch (Exception e) {
                                        Logging.error("Error redeeming WifiDB token: ", e);
                                        runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Network error redeeming token", Toast.LENGTH_SHORT).show());
                                    }
                                }).start();
                                try { image.close(); } catch (Exception ignored) {}
                                return;
                            }
                        }
                    })
                    .addOnFailureListener(e -> Logging.error("Failed to process image for barcodes: ", e))
                    .addOnCompleteListener(task -> {
                        try { image.close(); } catch (Exception ignored) {}
                    });
            }
        });

        OrientationEventListener orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                //textView.setText(Integer.toString(orientation));
            }
        };
        orientationEventListener.enable();
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        try {
            preview.setSurfaceProvider(cameraView.getSurfaceProvider());
            Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis, preview);
        } catch(Exception e) {
            Logging.error("failed to bind to preview: ", e);
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            Logging.info("Camera response permissions: " + Arrays.toString(permissions)
                    + " grantResults: " + Arrays.toString(grantResults));
            List<String> deniedPermissions = new ArrayList<>();
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i]);
                }
            }
            if (deniedPermissions.isEmpty()) {
                startCamera();
            } else {
                finish();
            }
        } else {
            Logging.info("Unhandled onRequestPermissionsResult code: " + requestCode);
        }
    }

    private void requestCameraPermission() {
        Logging.info( "Camera permissions have NOT been granted. Requesting....");
        ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA},
                REQUEST_CAMERA);
    }
}
