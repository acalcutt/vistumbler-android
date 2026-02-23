package net.wigle.wigleandroid.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import android.view.ViewGroup;

import net.wigle.wigleandroid.R;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.util.Logging;

/**
 * Based extensively on MapUtils {@link com.google.maps.android.ui.IconGenerator} - but retooled specifically for WiGLE's wireless network use case.
 * - provides network-specific icons
 * - color-codes background bubbles (still uses google 9patch images)
 * - offers BSSID labels for null/empty SSIDs networks
 * @see <a href="https://github.com/googlemaps/android-maps-utils">MapUtils</a>
 * @author arkasha
 */
public class NetworkIconGenerator {
    private final Context mContext;
    private TextView mTextView;
    private final ViewGroup mContainer;
    private final ViewGroup mRotationLayout;
    private final NetworkBubbleDrawable mBackground;
    private int mRotation;
    private View mContentView;
    private float mAnchorU = 0.5F;
    private float mAnchorV = 1.0F;


    public static final int STYLE_DEFAULT = 1;
    public static final int STYLE_WHITE = 2;
    public static final int STYLE_CELL = 3;
    public static final int STYLE_BT = 4;
    public static final int STYLE_WIFI = 5;
    public static final int STYLE_CELL_NEW = 6;
    public static final int STYLE_BT_NEW = 7;
    public static final int STYLE_WIFI_NEW = 8;


    /**
     * Creates a new IconGenerator with the default style.
     *
     * @param context the context for the NetworkIconGenerator
     */
    public NetworkIconGenerator(Context context) {
        mContext = context;
        mBackground = new NetworkBubbleDrawable(this.mContext.getResources());
        mContainer = (ViewGroup) LayoutInflater.from(mContext).inflate(R.layout.amu_text_bubble, null);
        mRotationLayout = (ViewGroup) mContainer.getChildAt(0);
        mContentView = mTextView = (TextView) mRotationLayout.findViewById(R.id.amu_text);
        setStyle(STYLE_DEFAULT);
    }

    public Bitmap makeIcon(@NonNull Network network, final boolean isNew) {
        if (this.mTextView != null) {
            final String ssid = network.getSsid();
            if (null != ssid && !ssid.isEmpty()) {
                this.mTextView.setText(ssid);
            } else {
                this.mTextView.setText(network.getBssid());
            };
            mTextView.setGravity(Gravity.CENTER_HORIZONTAL);
            mTextView.setCompoundDrawablePadding(mContext.getResources().getDimensionPixelSize(R.dimen.map_label_image_padding));
            mTextView.setCompoundDrawablesWithIntrinsicBounds(getIconId(network.getType(), network.getCrypto()), 0, 0, 0);
            setStyle(styleForNetworkType(network.getType(), isNew));
        }
        return this.makeIcon();
    }

    public Bitmap makeIcon() {
        int measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        mContainer.measure(measureSpec, measureSpec);

        int measuredWidth = mContainer.getMeasuredWidth();
        int measuredHeight = mContainer.getMeasuredHeight();

        mContainer.layout(0, 0, measuredWidth, measuredHeight);

        if (mRotation == 1 || mRotation == 3) {
            measuredHeight = mContainer.getMeasuredWidth();
            measuredWidth = mContainer.getMeasuredHeight();
        }

        Bitmap r = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888);
        r.eraseColor(Color.TRANSPARENT);

        Canvas canvas = new Canvas(r);
        switch (mRotation) {
            case 0:
                // do nothing
                break;
            case 1:
                canvas.translate(measuredWidth, 0);
                canvas.rotate(90);
                break;
            case 2:
                canvas.rotate(180, measuredWidth / 2.0f, measuredHeight / 2.0f);
                break;
            case 3:
                canvas.translate(0, measuredHeight);
                canvas.rotate(270);
                break;
        }
        mContainer.draw(canvas);
        return r;
    }

    public void setContentView(View contentView) {
        mRotationLayout.removeAllViews();
        mRotationLayout.addView(contentView);
        mContentView = contentView;
        final View view = mRotationLayout.findViewById(R.id.amu_text);
        mTextView = view instanceof TextView ? (TextView) view : null;
    }

    public void setContentRotation(int degrees) {
        // RotationLayout isn't available; set rotation on the content view as a simple fallback.
        if (mContentView != null) {
            mContentView.setRotation(degrees);
        }
        // store simplified rotation index used by makeIcon for bitmap rotation handling
        this.mRotation = ((degrees / 90) % 4 + 4) % 4;
    }

    private float rotateAnchor(float u, float v) {
        switch (this.mRotation) {
            case 0:
                return u;
            case 1:
                return 1.0F - v;
            case 2:
                return 1.0F - u;
            case 3:
                return v;
            default:
                throw new IllegalStateException();
        }
    }

    public float getAnchorU() {
        float mAnchorU = 0.5f;
        float mAnchorV = 1f;
        return rotateAnchor(mAnchorU, mAnchorV);
    }

    public void setStyle(int style) {
        this.setColor(getStyleColor(style));
        this.setTextAppearance(this.mContext, getTextStyle(style));
    }

    public void setTextAppearance(Context context, int resid) {
        if (this.mTextView != null) {
            this.mTextView.setTextAppearance(context, resid);
        }
    }

    public void setColor(int color) {
        this.mBackground.setColor(color);
        this.setBackground(this.mBackground);
    }

    public void setBackground(Drawable background) {
        this.mContainer.setBackgroundDrawable(background);
        if (background != null) {
            Rect rect = new Rect();
            background.getPadding(rect);
            this.mContainer.setPadding(rect.left, rect.top, rect.right, rect.bottom);
        } else {
            this.mContainer.setPadding(0, 0, 0, 0);
        }

    }

    public void setContentPadding(int left, int top, int right, int bottom) {
        this.mContentView.setPadding(left, top, right, bottom);
    }

    private static int getStyleColor(int style) {
        switch (style) {
            case STYLE_CELL:
                return -869072896; //CC330000
            case STYLE_CELL_NEW:
                return -866844672; //CC550000
            case STYLE_BT:
                return -872410829; //CC001133
            case STYLE_BT_NEW:
                return -872406443; //CC002255
            case STYLE_WIFI:
                return -871288064; //CC113300
            case STYLE_WIFI_NEW:
                return -870165248; //CC225500
            case STYLE_DEFAULT:
            case STYLE_WHITE:
            default:
                return -1;
        }
    }

    private static int getTextStyle(int style) {
        // Map-utils styles may not be available in this package; use Android device-default fallbacks.
        switch (style) {
            case STYLE_DEFAULT:
            case STYLE_WHITE:
            default:
                return android.R.style.TextAppearance_DeviceDefault_Small;
            case STYLE_CELL:
            case STYLE_BT:
            case STYLE_WIFI:
            case STYLE_CELL_NEW:
            case STYLE_BT_NEW:
            case STYLE_WIFI_NEW:
                return android.R.style.TextAppearance_DeviceDefault_Medium;
        }
    }


    private int getIconId(NetworkType type, int crypto) {
        switch (type) {
            case BT:
                return R.drawable.bt_white;
            case BLE:
                return R.drawable.btle_white;
            case NR:
                return R.drawable.ic_cell_5g;
            case GSM:
            case LTE:
            case CDMA:
            case WCDMA:
                return R.drawable.ic_cell;
            case WIFI:
                switch (crypto) {
                    case Network.CRYPTO_NONE:
                        return R.drawable.no_ico;
                    case Network.CRYPTO_WEP:
                        return R.drawable.wep_ico;
                    case Network.CRYPTO_WPA:
                        return R.drawable.wpa_ico;
                    case Network.CRYPTO_WPA2:
                        return R.drawable.wpa2_ico;
                    case Network.CRYPTO_WPA3:
                        return R.drawable.wpa3_ico;
                }
            default:
                return R.drawable.ic_wifi_sm;
        }
    }

    private int styleForNetworkType(NetworkType t, final boolean isNew) {
        switch (t) {
            case BT:
            case BLE:
                if (isNew) {
                    return STYLE_BT_NEW;
                }
                return STYLE_BT;
            case CDMA:
            case GSM:
            case WCDMA:
            case LTE:
            case NR:
                if (isNew) {
                    return STYLE_CELL_NEW;
                }
                return STYLE_CELL;
            case WIFI:
                if (isNew) {
                    return STYLE_WIFI_NEW;
                }
                return STYLE_WIFI;
            default:
                return STYLE_DEFAULT;
        }
    }
}
