package net.wigle.wigleandroid.util;

import android.graphics.Color;
import android.util.Log;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.VectorSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the WifiDB history overlays as runtime vector sources, one per bucket.
 *
 * Each bucket is its own published archive with its own TileJSON, so each gets its own
 * source ({@code WifiDB_<bucket>}) whose single vector layer is named after the bucket.
 * That replaces the older arrangement, where the buttons drew from three sources baked
 * into the style ({@code WifiDB_newest}, {@code WifiDB}, {@code WifiDB_cells}) and
 * several age tiers had to share one -- weekly, monthly and 0to1year all landed in
 * {@code WifiDB_newest}, and "3+ year" had no bucket behind it at all.
 *
 * Colors and radii are the same table VistumblerCS and VistumblerMAUI use, so the three
 * apps render the same history the same way: tiers start dimmer than the live scan
 * layer and darken with age.
 */
public final class WifiDbHistoryLayers {
    private static final String TAG = "WifiDbHistoryLayers";

    /** Canonical z-order, newest to oldest, wifi tiers then cell tiers. */
    public static final String[] BUCKET_ORDER = {
            "daily", "weekly", "monthly", "0to1year", "1to2year",
            "2to3year", "3to5year", "5to10year", "10yrplus",
            "cell_daily", "cell_weekly", "cell_monthly", "cell_0to1year",
            "cell_1to2year", "cell_2to3year", "cell_3to5year",
            "cell_5to10year", "cell_10yrplus",
    };

    private static final float OPACITY = 0.85f;

    /**
     * Per-bucket circle style. Cell buckets use a single graduated purple -- cells carry
     * a {@code type} (LTE/GSM/CDMA) rather than an open/WEP/secure split -- so their wep
     * and secure entries just repeat open.
     */
    private static final class BucketStyle {
        final String open;
        final String wep;
        final String secure;
        final float radius;

        BucketStyle(final String open, final String wep, final String secure, final float radius) {
            this.open = open;
            this.wep = wep;
            this.secure = secure;
            this.radius = radius;
        }
    }

    private static final BucketStyle DEFAULT_STYLE =
            new BucketStyle("#12a642", "#a66d20", "#a61111", 3.0f);

    private static final Map<String, BucketStyle> STYLES = new HashMap<String, BucketStyle>();

    static {
        STYLES.put("daily", new BucketStyle("#12a642", "#a66d20", "#a61111", 3.0f));
        STYLES.put("weekly", new BucketStyle("#109a3d", "#9a641e", "#9a1010", 3.0f));
        STYLES.put("monthly", new BucketStyle("#0e8d38", "#8d5c1b", "#8d0f0f", 3.0f));
        STYLES.put("0to1year", new BucketStyle("#0d8033", "#805319", "#800e0e", 3.0f));
        STYLES.put("1to2year", new BucketStyle("#0b732e", "#734b16", "#730c0c", 2.75f));
        STYLES.put("2to3year", new BucketStyle("#0a6629", "#664213", "#660b0b", 2.5f));
        STYLES.put("3to5year", new BucketStyle("#085924", "#593a11", "#590a0a", 2.25f));
        STYLES.put("5to10year", new BucketStyle("#07401a", "#40290c", "#400707", 2.0f));
        STYLES.put("10yrplus", new BucketStyle("#052e13", "#2e1e09", "#2e0505", 1.5f));
        STYLES.put("cell_daily", new BucketStyle("#b296e3", "#b296e3", "#b296e3", 3.0f));
        STYLES.put("cell_weekly", new BucketStyle("#9d78d8", "#9d78d8", "#9d78d8", 3.0f));
        STYLES.put("cell_monthly", new BucketStyle("#885fcd", "#885fcd", "#885fcd", 3.0f));
        STYLES.put("cell_0to1year", new BucketStyle("#885fcd", "#885fcd", "#885fcd", 3.0f));
        STYLES.put("cell_1to2year", new BucketStyle("#7a4dc0", "#7a4dc0", "#7a4dc0", 2.75f));
        STYLES.put("cell_2to3year", new BucketStyle("#6f40b3", "#6f40b3", "#6f40b3", 2.5f));
        STYLES.put("cell_3to5year", new BucketStyle("#5e3599", "#5e3599", "#5e3599", 2.25f));
        STYLES.put("cell_5to10year", new BucketStyle("#4d2b80", "#4d2b80", "#4d2b80", 2.0f));
        STYLES.put("cell_10yrplus", new BucketStyle("#3d2266", "#3d2266", "#3d2266", 1.5f));
    }

    /** Short button label per bucket. */
    private static final Map<String, String> LABELS = new HashMap<String, String>();

    static {
        LABELS.put("daily", "Day");
        LABELS.put("weekly", "Week");
        LABELS.put("monthly", "Month");
        LABELS.put("0to1year", "0-1 year");
        LABELS.put("1to2year", "1-2 year");
        LABELS.put("2to3year", "2-3 year");
        LABELS.put("3to5year", "3-5 year");
        LABELS.put("5to10year", "5-10 year");
        LABELS.put("10yrplus", "10+ year");
        LABELS.put("cell_daily", "Cell Day");
        LABELS.put("cell_weekly", "Cell Week");
        LABELS.put("cell_monthly", "Cell Month");
        LABELS.put("cell_0to1year", "Cell 0-1 year");
        LABELS.put("cell_1to2year", "Cell 1-2 year");
        LABELS.put("cell_2to3year", "Cell 2-3 year");
        LABELS.put("cell_3to5year", "Cell 3-5 year");
        LABELS.put("cell_5to10year", "Cell 5-10 year");
        LABELS.put("cell_10yrplus", "Cell 10+ year");
    }

    private WifiDbHistoryLayers() {
    }

    public static boolean isKnownBucket(final String bucket) {
        return bucket != null && STYLES.containsKey(bucket);
    }

    /** True for the cell mirror of a tier, which draws as one layer rather than three. */
    public static boolean isCell(final String bucket) {
        return bucket != null && bucket.startsWith("cell_");
    }

    public static String labelFor(final String bucket) {
        final String label = LABELS.get(bucket);
        return label == null ? bucket : label;
    }

    /** Runtime source id for a bucket. One archive, one source. */
    public static String sourceIdFor(final String bucket) {
        return "WifiDB_" + bucket;
    }

    /**
     * Layer ids for a bucket, in the order they are added. Wifi tiers get three layers
     * split by sectype; cell tiers get one.
     */
    public static String[] layerIdsFor(final String bucket) {
        if (isCell(bucket)) {
            return new String[]{"hist_" + bucket + "_circles"};
        }
        return new String[]{
                "hist_" + bucket + "_open",
                "hist_" + bucket + "_wep",
                "hist_" + bucket + "_secure",
        };
    }

    /** True once this bucket's layers are in the style. */
    public static boolean isAdded(final Style style, final String bucket) {
        if (style == null) return false;
        try {
            return style.getLayer(layerIdsFor(bucket)[0]) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Add this bucket's source and layers to the style.
     *
     * The source's TileJSON URL comes from {@link WifiDbTileSources}, which is what
     * points the layer at the bucket's published archive; the vector layer inside that
     * archive is named after the bucket, which is what {@code setSourceLayer} needs.
     *
     * @param topAnchorLayerId layer the newest bucket sits directly below, normally the
     *                         live-scan layer so local points stay on top.
     * @return true if the layers were added.
     */
    public static boolean add(final Style style, final String bucket, final String topAnchorLayerId) {
        if (style == null || !isKnownBucket(bucket)) return false;

        final String sourceId = sourceIdFor(bucket);
        try {
            if (style.getSource(sourceId) == null) {
                final String url = WifiDbTileSources.tileJsonUrlFor(bucket);
                style.addSource(new VectorSource(sourceId, url));
                Log.i(TAG, "added source " + sourceId + " -> " + url);
            }
        } catch (Exception ex) {
            Log.w(TAG, "failed to add source " + sourceId + ": " + ex.getMessage());
            return false;
        }

        final BucketStyle bs = styleFor(bucket);
        final String anchor = belowLayerFor(style, bucket, topAnchorLayerId);
        final String[] layerIds = layerIdsFor(bucket);

        try {
            if (isCell(bucket)) {
                addLayer(style, circleLayer(layerIds[0], sourceId, bucket, bs.open, bs.radius, null), anchor);
            } else {
                addLayer(style, circleLayer(layerIds[0], sourceId, bucket, bs.open, bs.radius, 1), anchor);
                addLayer(style, circleLayer(layerIds[1], sourceId, bucket, bs.wep, bs.radius, 2), anchor);
                addLayer(style, circleLayer(layerIds[2], sourceId, bucket, bs.secure, bs.radius, 3), anchor);
            }
        } catch (Exception ex) {
            Log.w(TAG, "failed to add layers for " + bucket + ": " + ex.getMessage());
            return false;
        }

        Log.i(TAG, "added " + bucket + " below " + anchor);
        return true;
    }

    /** Remove this bucket's layers and source. */
    public static void remove(final Style style, final String bucket) {
        if (style == null || bucket == null) return;
        for (final String layerId : layerIdsFor(bucket)) {
            try {
                style.removeLayer(layerId);
            } catch (Exception ignored) {
            }
        }
        try {
            style.removeSource(sourceIdFor(bucket));
        } catch (Exception ignored) {
        }
    }

    // -- internals ------------------------------------------------------------

    private static BucketStyle styleFor(final String bucket) {
        final BucketStyle bs = STYLES.get(bucket);
        return bs == null ? DEFAULT_STYLE : bs;
    }

    /**
     * The layer this bucket goes directly below: the nearest newer bucket already in the
     * style, or the top anchor. Reading it back off the style rather than tracking it
     * means the stack comes out in age order however the buttons were pressed.
     */
    private static String belowLayerFor(final Style style, final String bucket, final String topAnchorLayerId) {
        int idx = -1;
        for (int i = 0; i < BUCKET_ORDER.length; i++) {
            if (BUCKET_ORDER[i].equals(bucket)) {
                idx = i;
                break;
            }
        }
        for (int i = idx - 1; i >= 0; i--) {
            final String candidate = layerIdsFor(BUCKET_ORDER[i])[0];
            try {
                if (style.getLayer(candidate) != null) return candidate;
            } catch (Exception ignored) {
            }
        }
        return topAnchorLayerId;
    }

    private static CircleLayer circleLayer(final String layerId, final String sourceId,
                                           final String sourceLayer, final String color,
                                           final float radius, final Integer sectype) {
        final CircleLayer layer = new CircleLayer(layerId, sourceId);
        layer.setSourceLayer(sourceLayer);
        layer.setProperties(
                PropertyFactory.circleColor(Color.parseColor(color)),
                PropertyFactory.circleRadius(radius),
                PropertyFactory.circleOpacity(OPACITY)
        );
        if (sectype != null) {
            // Archives have carried sectype as a number since the PMTiles rebuild, but the
            // string form is cheap to keep matching and costs nothing when it is absent.
            layer.setFilter(Expression.any(
                    Expression.eq(Expression.get("sectype"), Expression.literal(sectype)),
                    Expression.eq(Expression.get("sectype"), Expression.literal(String.valueOf(sectype)))
            ));
        }
        return layer;
    }

    private static void addLayer(final Style style, final CircleLayer layer, final String anchor) {
        if (anchor != null) {
            try {
                style.addLayerBelow(layer, anchor);
                return;
            } catch (Exception ignored) {
            }
        }
        style.addLayer(layer);
    }
}
