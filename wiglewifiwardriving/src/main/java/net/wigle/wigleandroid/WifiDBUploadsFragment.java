package net.wigle.wigleandroid;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import net.wigle.wigleandroid.background.BackgroundGuiHandler;
import net.wigle.wigleandroid.background.ObservationUploader;
import net.wigle.wigleandroid.model.api.UploadReseponse;
import net.wigle.wigleandroid.net.RequestCompletedListener;
import net.wigle.wigleandroid.net.WifiDBApiManager;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class WifiDBUploadsFragment extends Fragment {

    private WifiDBApiManager wifiDBApiManager;
    private TextView scheduleDetails;
    private boolean clearAfterThisUpload = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_wifidb_uploads, container, false);

        wifiDBApiManager = new WifiDBApiManager(getContext());

        Button uploadRunButton = view.findViewById(R.id.button_upload_run);
        uploadRunButton.setOnClickListener(v -> startWriteAndUpload(false, false));

        Button uploadRunAndClearButton = view.findViewById(R.id.button_upload_run_and_clear);
        uploadRunAndClearButton.setOnClickListener(v -> startWriteAndUpload(true, false));

        Button uploadFullDbButton = view.findViewById(R.id.button_upload_full_db);
        uploadFullDbButton.setOnClickListener(v -> startWriteAndUpload(false, true));

        Button uploadFullDbAndClearButton = view.findViewById(R.id.button_upload_full_db_and_clear);
        uploadFullDbAndClearButton.setOnClickListener(v -> startWriteAndUpload(true, true));

        scheduleDetails = view.findViewById(R.id.text_schedule_details);
        getSchedule();

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void startWriteAndUpload(final boolean clearAfter, final boolean uploadAll) {
        this.clearAfterThisUpload = clearAfter;
        // start an ObservationUploader that only writes the current run file (justWriteFile=true, writeRun=true)
        try {
            final SharedPreferences prefs = getActivity().getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
            final String uploadPath = prefs.getString(PreferenceKeys.PREF_WIFIDB_UPLOAD_FOLDER,
                    Environment.getExternalStorageDirectory().getAbsolutePath() + "/wifidb");
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            final String filename = "WigleWifi_" + sdf.format(new Date()) + ".csv.gz";

            final Bundle bundle = new Bundle();
            bundle.putString(BackgroundGuiHandler.FILEPATH, uploadPath);
            bundle.putString(BackgroundGuiHandler.FILENAME, filename);
            final String wifiDbUriString = prefs.getString(PreferenceKeys.PREF_WIFIDB_UPLOAD_FOLDER, null);
            final Uri wifiDbUri = wifiDbUriString != null ? Uri.parse(wifiDbUriString) : null;
            final String uploadUrl = prefs.getString(PreferenceKeys.PREF_WIFIDB_URL, "");

            final ObservationUploader ou = new ObservationUploader(getActivity(), ListFragment.lameStatic.dbHelper, (object, cached) -> {
                try {
                    final String fileUriString = object.getString(BackgroundGuiHandler.FILE_URI);
                    if (fileUriString != null) {
                        final Uri fileUri = Uri.parse(fileUriString);
                        uploadFileToWifiDB(fileUri, filename);
                    }
                } catch (final JSONException e) {
                    Logging.error("Error getting bundle from result", e);
                }
            }, true, uploadAll, !uploadAll, null, null, bundle, wifiDbUri, uploadUrl);
            ou.startDownload(null);
            scheduleDetails.setText(R.string.upload_preparing_toast);
        } catch (Exception ex) {
            Logging.error("Failed to start export: ", ex);
            scheduleDetails.setText(R.string.upload_export_fail_toast);
        }
    }

    private void uploadFileToWifiDB(final Uri fileUri, final String filename) {
        final Map<String,String> params = new HashMap<>();
        params.put("title", filename);
        wifiDBApiManager.uploadToWifiDB(fileUri, params, new Handler(Looper.getMainLooper()), new RequestCompletedListener<UploadReseponse, JSONObject>() {
            @Override
            public void onTaskSucceeded(UploadReseponse response) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), R.string.upload_successful_toast, Toast.LENGTH_SHORT).show();
                        scheduleDetails.setText(R.string.upload_successful_toast);
                        Logging.info("WifiDB Upload Response: " + response);
                    });
                }
                if (clearAfterThisUpload) {
                    try {
                        ListFragment.lameStatic.dbHelper.clearDatabase();
                        final SharedPreferences prefs = getActivity().getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                        final SharedPreferences.Editor editor = prefs.edit();
                        editor.putLong(PreferenceKeys.PREF_DB_MARKER, 0L);
                        editor.apply();
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> scheduleDetails.append("\n" + getString(R.string.upload_cleared_toast)));
                        }
                    } catch (Exception ex) {
                        Logging.error("Failed to clear DB after upload: ", ex);
                    }
                }
            }

            @Override
            public void onTaskFailed(int status, JSONObject error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), R.string.upload_failed_toast, Toast.LENGTH_SHORT).show();
                        String msg = getString(R.string.upload_failed_toast) + ": " + status;
                        if (error != null) {
                            try {
                                if (error.has("error")) {
                                    String serr = error.getString("error");
                                    if (serr != null && !serr.isEmpty()) msg += " - " + serr;
                                } else if (error.has("message")) {
                                    String serr = error.getString("message");
                                    if (serr != null && !serr.isEmpty()) msg += " - " + serr;
                                }
                            } catch (JSONException je) {
                                Logging.error("Error reading upload error JSON", je);
                            }
                        }
                        scheduleDetails.setText(msg);
                    });
                }
            }

            @Override
            public void onTaskCompleted() {
                //
            }
        });
    }

    private void uploadFileToWifiDB(final String absPath) {
        final Map<String,String> params = new HashMap<>();
        wifiDBApiManager.uploadToWifiDB(absPath, params, new Handler(Looper.getMainLooper()), new RequestCompletedListener<UploadReseponse, JSONObject>() {
            @Override
            public void onTaskSucceeded(UploadReseponse response) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), R.string.upload_successful_toast, Toast.LENGTH_SHORT).show();
                        scheduleDetails.setText(R.string.upload_successful_toast);
                    });
                }
                if (clearAfterThisUpload) {
                    try {
                        ListFragment.lameStatic.dbHelper.clearDatabase();
                        final SharedPreferences prefs = getActivity().getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
                        final SharedPreferences.Editor editor = prefs.edit();
                        editor.putLong(PreferenceKeys.PREF_DB_MARKER, 0L);
                        editor.apply();
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> scheduleDetails.append("\n" + getString(R.string.upload_cleared_toast)));
                        }
                    } catch (Exception ex) {
                        Logging.error("Failed to clear DB after upload: ", ex);
                    }
                }
            }

            @Override
            public void onTaskFailed(int status, JSONObject error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), R.string.upload_failed_toast, Toast.LENGTH_SHORT).show();
                        String msg = getString(R.string.upload_failed_toast) + ": " + status;
                        if (error != null) {
                            try {
                                if (error.has("error")) {
                                    String serr = error.getString("error");
                                    if (serr != null && !serr.isEmpty()) msg += " - " + serr;
                                } else if (error.has("message")) {
                                    String serr = error.getString("message");
                                    if (serr != null && !serr.isEmpty()) msg += " - " + serr;
                                }
                            } catch (JSONException je) {
                                Logging.error("Error reading upload error JSON", je);
                            }
                        }
                        scheduleDetails.setText(msg);
                    });
                }
            }

            @Override
            public void onTaskCompleted() {
                //
            }
        });
    }

    private void getSchedule() {
        wifiDBApiManager.getSchedule(new RequestCompletedListener<String, JSONObject>() {
            @Override
            public void onTaskSucceeded(String response) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> scheduleDetails.setText(response));
                }
            }

            @Override
            public void onTaskFailed(int status, JSONObject error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> scheduleDetails.setText(R.string.uploads_no_schedule));
                }
            }

            @Override
            public void onTaskCompleted() {
                //
            }
        });
    }
}
