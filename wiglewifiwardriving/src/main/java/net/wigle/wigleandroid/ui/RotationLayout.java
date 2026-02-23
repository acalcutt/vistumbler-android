package net.wigle.wigleandroid.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Minimal replacement for google-maps-utils RotationLayout used for icon generation.
 * Rotates its single child view by the requested degrees.
 */
public class RotationLayout extends ViewGroup {
    private int mRotation = 0;

    public RotationLayout(Context context) {
        super(context);
    }

    public RotationLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotationLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setViewRotation(int degrees) {
        mRotation = degrees % 360;
        if (getChildCount() > 0) {
            View v = getChildAt(0);
            v.setRotation(mRotation);
            // adjust pivot to center
            v.setPivotX(v.getMeasuredWidth() / 2f);
            v.setPivotY(v.getMeasuredHeight() / 2f);
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();
        int maxWidth = 0;
        int maxHeight = 0;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
                maxWidth = Math.max(maxWidth, child.getMeasuredWidth());
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight());
            }
        }
        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec), resolveSize(maxHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int count = getChildCount();
        int parentWidth = r - l;
        int parentHeight = b - t;
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                int left = (parentWidth - cw) / 2;
                int top = (parentHeight - ch) / 2;
                child.layout(left, top, left + cw, top + ch);
                // ensure pivot centered
                child.setPivotX(cw / 2f);
                child.setPivotY(ch / 2f);
                child.setRotation(mRotation);
            }
        }
    }
}
