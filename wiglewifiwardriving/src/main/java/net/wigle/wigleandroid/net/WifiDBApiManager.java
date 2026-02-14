package net.wigle.wigleandroid.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import net.wigle.wigleandroid.background.BackgroundGuiHandler;
import net.wigle.wigleandroid.background.CountingRequestBody;
import net.wigle.wigleandroid.model.api.UploadReseponse;
import net.wigle.wigleandroid.util.Logging;
import net.wigle.wigleandroid.util.PreferenceKeys;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.File;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class WifiDBApiManager {
    private static final int LOCAL_FAILURE_CODE = 999;
    private final OkHttpClient client;
    private final Context context;

    public WifiDBApiManager(Context context) {
        this.client = new OkHttpClient();
        this.context = context;
    }

    public void uploadToWifiDB(@NotNull final Uri fileUri,
                               @NotNull final Map<String, String> params,
                               final Handler handler,
                               @NotNull final RequestCompletedListener<UploadReseponse,
            JSONObject> completedListener) {
        final SharedPreferences prefs = context.getSharedPreferences(net.wigle.wigleandroid.util.PreferenceKeys.SHARED_PREFS, 0);
        final String url = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_URL, "");
        Logging.info("WifiDB Upload URL: " + url);
        if (url == null || url.isEmpty()) {
            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
            return;
        }

        try {
            final String scheme = fileUri.getScheme();
            File uploadFile;
            boolean deleteAfter;

            if (scheme != null && scheme.equalsIgnoreCase("file")) {
                // Use the File directly when given a file:// URI to avoid an extra copy
                uploadFile = new File(fileUri.getPath());
                deleteAfter = false;
                if (!uploadFile.exists() || !uploadFile.canRead()) {
                    throw new IOException("Unable to access file for URI: " + fileUri);
                }
            } else {
                // try to discover original filename so we can preserve extension
                String originalName = null;
                Cursor cursor = null;
                try {
                    cursor = context.getContentResolver().query(fileUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx != -1) originalName = cursor.getString(idx);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
                }

                String suffix = ".tmp";
                if (originalName != null) {
                    String on = originalName.toLowerCase();
                    if (on.endsWith(".csv.gz")) suffix = ".csv.gz";
                    else {
                        int dot = on.lastIndexOf('.');
                        if (dot != -1 && dot < on.length()-1) suffix = on.substring(dot);
                    }
                }

                // copy Uri stream to a temp file so OkHttp can determine content length
                final File tmpFile = File.createTempFile("wifedb_upload_", suffix, context.getCacheDir());
                try (InputStream fileInputStream = context.getContentResolver().openInputStream(fileUri)) {
                    if (fileInputStream == null) throw new IOException("Unable to open input stream for URI");
                    try (BufferedSink out = Okio.buffer(Okio.sink(tmpFile))) {
                        out.writeAll(Okio.source(fileInputStream));
                    }
                }
                uploadFile = tmpFile;
                deleteAfter = true;
            }

            // pick media type by filename
            final String nameLower = uploadFile.getName().toLowerCase();
            final MediaType mediaType;
            if (nameLower.endsWith(".gz") || nameLower.endsWith(".tgz")) {
                mediaType = MediaType.parse("application/gzip");
            } else if (nameLower.endsWith(".csv")) {
                mediaType = MediaType.parse("text/csv");
            } else {
                mediaType = MediaType.parse("application/octet-stream");
            }

            RequestBody fileBody = RequestBody.create(uploadFile, mediaType);

                // prefer explicit title param as filename so the server can detect extension
                String multipartFilename = params.get("title");
                if (multipartFilename == null || multipartFilename.isEmpty()) {
                multipartFilename = uploadFile.getName();
                }

                // If the provided filename indicates a gzipped CSV, compress the upload if needed
                final File originalUploadFile = uploadFile;
                final boolean createdOriginalTmp = deleteAfter; // true if we created the tmp file
                final boolean[] createdGz = {false};
                final File[] gzFile = {null};
                String multipartFilenameLower = multipartFilename.toLowerCase();
                if (multipartFilenameLower.endsWith(".csv.gz") && !uploadFile.getName().toLowerCase().endsWith(".gz")) {
                    // create gzipped temp file
                    gzFile[0] = File.createTempFile("wifedb_upload_gz_", ".csv.gz", context.getCacheDir());
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(uploadFile);
                         java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(gzFile[0]))) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = fis.read(buf)) > 0) {
                            gos.write(buf, 0, len);
                        }
                    }
                    uploadFile = gzFile[0];
                    createdGz[0] = true;
                    deleteAfter = true; // ensure gz file is removed after upload
                    // pick gzip media type
                    fileBody = RequestBody.create(uploadFile, MediaType.parse("application/gzip"));
                    if (params.get("title") == null) {
                        multipartFilename = uploadFile.getName();
                    }
                }

                MultipartBody.Builder builder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", multipartFilename, fileBody);

            // add configured username/apikey if present
            final String wuser = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_USERNAME, "");
            final String wapikey = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_APIKEY, "");
            if (wuser != null && !wuser.isEmpty()) builder.addFormDataPart("username", wuser);
            if (wapikey != null && !wapikey.isEmpty()) builder.addFormDataPart("apikey", wapikey);

            // add user-supplied params
            if (!params.isEmpty()) {
                for ( Map.Entry<String, String> entry : params.entrySet() ) {
                    builder.addFormDataPart(entry.getKey(), entry.getValue());
                }
            }
            Logging.info("WifiDB Upload Params: " + params.toString());

            MultipartBody multipartBody = builder.build();
            CountingRequestBody countingBody
                    = new CountingRequestBody(multipartBody, (bytesWritten, contentLength) -> {
                int progress = (int)((bytesWritten*1000) / contentLength );
                Logging.info("progress: "+ progress + "("+bytesWritten +" /"+contentLength+")");
                if ( handler != null && progress >= 0 ) {
                    handler.sendEmptyMessage( BackgroundGuiHandler.WRITING_PERCENT_START + progress );
                }
            });

            Request request = new Request.Builder()
                    .url(url + "v2/import.php")
                    .post(countingBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    // cleanup temp files we created
                        try {
                            if (createdGz[0] && gzFile[0] != null) gzFile[0].delete();
                        } catch (Exception ignored) {}
                        try {
                            if (createdOriginalTmp && originalUploadFile != null && originalUploadFile.exists()) originalUploadFile.delete();
                        } catch (Exception ignored) {}
                    if (!response.isSuccessful()) {
                        Logging.error("Failed to upload file to WifiDB: " + response.code() + " " + response.message());
                        completedListener.onTaskFailed(response.code(), null);
                    } else {
                        if (null != response.body()) {
                            try (ResponseBody responseBody = response.body()) {
                                final String responseBodyString = responseBody.string();
                                    Logging.info("WifiDB Upload Response: " + responseBodyString);
                                    try {
                                        final org.json.JSONObject json = new org.json.JSONObject(responseBodyString);
                                        if (json.has("error")) {
                                            // server returned an error payload
                                            completedListener.onTaskFailed(response.code(), json);
                                        } else if (json.has("import")) {
                                            // WifiDB v2 returns an "import" object on success
                                            final UploadReseponse r = new UploadReseponse();
                                            r.setSuccess(Boolean.TRUE);
                                            final UploadReseponse.UploadResultsResponse res = r.new UploadResultsResponse();
                                            final org.json.JSONObject imp = json.getJSONObject("import");
                                            res.setFilename(imp.optString("title", imp.optString("filename", "")));
                                            r.setResults(res);
                                            completedListener.onTaskSucceeded(r);
                                        } else {
                                            // try to parse into UploadReseponse (fallback)
                                            final UploadReseponse r = new Gson().fromJson(responseBodyString,
                                                    UploadReseponse.class);
                                            if (r != null && Boolean.TRUE.equals(r.getSuccess())) {
                                                completedListener.onTaskSucceeded(r);
                                            } else {
                                                completedListener.onTaskFailed(response.code(), null);
                                            }
                                        }
                                    } catch (final Exception e) {
                                        Logging.error("Failed to parse WifiDB response: ", e);
                                        completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                                    }
                            } catch (JsonSyntaxException e) {
                                completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                            }
                        } else {
                            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                        }
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    // cleanup temp files we created
                        try {
                            if (createdGz[0] && gzFile[0] != null) gzFile[0].delete();
                        } catch (Exception ignored) {}
                        try {
                            if (createdOriginalTmp && originalUploadFile != null && originalUploadFile.exists()) originalUploadFile.delete();
                        } catch (Exception ignored) {}
                    if (null != e) {
                        Logging.error("Failed to upload to WifiDB - client exception: "+ e.getClass() + " - " + e.getMessage());
                    } else {
                        Logging.error("Failed to upload to WifiDB - client call failed.");
                    }
                    completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                }
            });
        } catch (IOException e) {
            Logging.error("Failed to read file for WifiDB upload: ", e);
            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
        }
    }

    public void uploadToWifiDB(@NotNull final String filename,
                               @NotNull final Map<String, String> params,
                               final Handler handler,
                               @NotNull final RequestCompletedListener<UploadReseponse,
            JSONObject> completedListener) {
        final SharedPreferences prefs = context.getSharedPreferences(net.wigle.wigleandroid.util.PreferenceKeys.SHARED_PREFS, 0);
        final String url = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_URL, "");
        Logging.info("WifiDB Upload URL: " + url);
        if (url == null || url.isEmpty()) {
            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
            return;
        }

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", new File(filename).getName(),
                        RequestBody.create(new File(filename), MediaType.parse("application/gzip")));

        // add configured username/apikey if present
        final String wuser = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_USERNAME, "");
        final String wapikey = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_APIKEY, "");
        if (wuser != null && !wuser.isEmpty()) builder.addFormDataPart("username", wuser);
        if (wapikey != null && !wapikey.isEmpty()) builder.addFormDataPart("apikey", wapikey);

        // add user-supplied params
        if (!params.isEmpty()) {
            for ( Map.Entry<String, String> entry : params.entrySet() ) {
                builder.addFormDataPart(entry.getKey(), entry.getValue());
            }
        }
        Logging.info("WifiDB Upload Params: " + params.toString());

        MultipartBody requestBody = builder.build();
        CountingRequestBody countingBody
                = new CountingRequestBody(requestBody, (bytesWritten, contentLength) -> {
            int progress = (int)((bytesWritten*1000) / contentLength );
            Logging.info("progress: "+ progress + "("+bytesWritten +" /"+contentLength+")");
            if ( handler != null && progress >= 0 ) {
                handler.sendEmptyMessage( BackgroundGuiHandler.WRITING_PERCENT_START + progress );
            }
        });

        Request request = new Request.Builder()
                .url(url + "v2/import.php")
                .post(countingBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Logging.error("Failed to upload file to WifiDB: " + response.code() + " " + response.message());
                    completedListener.onTaskFailed(response.code(), null);
                } else {
                    if (null != response.body()) {
                        try (ResponseBody responseBody = response.body()) {
                                final String responseBodyString = responseBody.string();
                                Logging.info("WifiDB Upload Response: " + responseBodyString);
                                try {
                                    final org.json.JSONObject json = new org.json.JSONObject(responseBodyString);
                                    if (json.has("error")) {
                                        completedListener.onTaskFailed(response.code(), json);
                                    } else if (json.has("import")) {
                                        final UploadReseponse r = new UploadReseponse();
                                        r.setSuccess(Boolean.TRUE);
                                        final UploadReseponse.UploadResultsResponse res = r.new UploadResultsResponse();
                                        final org.json.JSONObject imp = json.getJSONObject("import");
                                        res.setFilename(imp.optString("title", imp.optString("filename", "")));
                                        r.setResults(res);
                                        completedListener.onTaskSucceeded(r);
                                    } else {
                                        final UploadReseponse r = new Gson().fromJson(responseBodyString,
                                                UploadReseponse.class);
                                        if (r != null && Boolean.TRUE.equals(r.getSuccess())) {
                                            completedListener.onTaskSucceeded(r);
                                        } else {
                                            completedListener.onTaskFailed(response.code(), null);
                                        }
                                    }
                                } catch (final Exception e) {
                                    Logging.error("Failed to parse WifiDB response: ", e);
                                    completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                                }
                        } catch (JsonSyntaxException e) {
                            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                        }
                    } else {
                        completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (null != e) {
                    Logging.error("Failed to upload to WifiDB - client exception: "+ e.getClass() + " - " + e.getMessage());
                } else {
                    Logging.error("Failed to upload to WifiDB - client call failed.");
                }
                completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
            }
        });
    }

    public void getSchedule(final RequestCompletedListener<String, JSONObject> completedListener) {
        final SharedPreferences prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, 0);
        final String url = prefs.getString(PreferenceKeys.PREF_WIFIDB_URL, "");
        if (url == null || url.isEmpty()) {
            completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
            return;
        }

        HttpUrl.Builder httpBuilder = HttpUrl.parse(url + "v2/schedule.php").newBuilder();
        httpBuilder.addQueryParameter("func", "waiting");
        final String wuser = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_USERNAME, "");
        final String wapikey = prefs.getString(net.wigle.wigleandroid.util.PreferenceKeys.PREF_WIFIDB_APIKEY, "");
        if (wuser != null && !wuser.isEmpty()) httpBuilder.addQueryParameter("username", wuser);
        if (wapikey != null && !wapikey.isEmpty()) httpBuilder.addQueryParameter("apikey", wapikey);

        Request request = new Request.Builder()
                .url(httpBuilder.build())
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Logging.error("Failed to get schedule from WifiDB: " + response.code() + " " + response.message());
                    completedListener.onTaskFailed(response.code(), null);
                } else {
                    if (null != response.body()) {
                        try (ResponseBody responseBody = response.body()) {
                            completedListener.onTaskSucceeded(responseBody.string());
                        }
                    } else {
                        completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (null != e) {
                    Logging.error("Failed to get schedule from WifiDB - client exception: "+ e.getClass() + " - " + e.getMessage());
                } else {
                    Logging.error("Failed to get schedule from WifiDB - client call failed.");
                }
                completedListener.onTaskFailed(LOCAL_FAILURE_CODE, null);
            }
        });
    }
}
