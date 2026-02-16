package net.wigle.wigleandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import net.wigle.wigleandroid.background.ObservationUploader;
import net.wigle.wigleandroid.background.BackgroundGuiHandler;
import net.wigle.wigleandroid.db.DBException;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.model.api.UploadReseponse;
import net.wigle.wigleandroid.net.RequestCompletedListener;
import net.wigle.wigleandroid.net.WifiDBApiManager;
import net.wigle.wigleandroid.util.PreferenceKeys;
import net.wigle.wigleandroid.util.Logging;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple Uploads screen to interact with WifiDB API
 */
public class UploadsActivity extends AppCompatActivity {

    private TextView statusView;
    private BroadcastReceiver fileReadyReceiver;
    private boolean clearAfterThisUpload = false;
    private WifiDBApiManager wifiDBApiManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uploads);

        statusView = findViewById(R.id.uploads_status);
        Button bUpload = findViewById(R.id.button_upload);
        Button bUploadClear = findViewById(R.id.button_upload_and_clear);

        wifiDBApiManager = new WifiDBApiManager(this);

        bUpload.setOnClickListener(v -> startWriteAndUpload(false));
        bUploadClear.setOnClickListener(v -> startWriteAndUpload(true));

        fileReadyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                final String filename = intent.getStringExtra(BackgroundGuiHandler.FILENAME);
                final String filepath = intent.getStringExtra(BackgroundGuiHandler.FILEPATH);
                if (filename != null) {
                    String absPath;
                    if (filepath != null) {
                        absPath = filepath + filename;
                    } else {
                        File f = getFileStreamPath(filename);
                        absPath = f != null ? f.getAbsolutePath() : filename;
                    }
                    statusView.setText("File ready: " + filename + "\nUploading...");
                    uploadFileToWifiDB(absPath);
                }
            }
        };

        registerReceiver(fileReadyReceiver, new IntentFilter("net.wigle.wigleandroid.FILE_READY"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(fileReadyReceiver); } catch (Exception ignored) {}
    }

    private void startWriteAndUpload(final boolean clearAfter) {
        this.clearAfterThisUpload = clearAfter;
        // start an ObservationUploader that only writes the current run file (justWriteFile=true, writeRun=true)
        try {
            final ObservationUploader ou = new ObservationUploader(this, ListFragment.lameStatic.dbHelper, null, true, false, true);
            // For write-only exports we can start the background thread directly to avoid triggering
            // token or prefs-based blocking logic in startDownload().
            ou.start();
            statusView.setText("Preparing export...");
        } catch (Exception ex) {
            Logging.error("Failed to start export: ", ex);
            statusView.setText("Failed to start export");
        }
    }

    private void uploadFileToWifiDB(final String absPath) {
        final Map<String,String> params = new HashMap<>();
        wifiDBApiManager.uploadToWifiDB(absPath, params, null, new RequestCompletedListener<UploadReseponse, JSONObject>() {
            @Override
            public void onTaskCompleted() { }

            @Override
            public void onTaskSucceeded(UploadReseponse response) {
                runOnUiThread(() -> statusView.setText("Upload successful"));
                if (clearAfterThisUpload) {
                    try {
                        ListFragment.lameStatic.dbHelper.clearDatabase();
                        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                        final SharedPreferences.Editor editor = prefs.edit();
                        editor.putLong(PreferenceKeys.PREF_DB_MARKER, 0L);
                        editor.apply();
                        // Update the network count so UI reflects the cleared state
                        try {
                            ListFragment.lameStatic.dbHelper.getNetworkCountFromDB();
                        } catch (DBException dbe) {
                            Logging.warn("Failed to update network count on DB clear: ", dbe);
                        }
                        runOnUiThread(() -> statusView.append("\nLocal DB cleared"));
                    } catch (Exception ex) {
                        Logging.error("Failed to clear DB after upload: ", ex);
                    }
                }
            }

            @Override
            public void onTaskFailed(int httpStatus, JSONObject error) {
                runOnUiThread(() -> statusView.setText("Upload failed: " + httpStatus));
            }
        });
    }
}
