package com.vaonis.vesperahelper;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.ScrollView;

/**
 * ScrollView that stays put after taps and content refreshes. Android otherwise
 * scrolls to the focused descendant (or to 0 after removeAllViews), which makes
 * the tab jump back to the top when a button is pressed.
 */
final class FixedScrollView extends ScrollView {
    private final int touchSlop;
    private int pinnedX;
    private int pinnedY;
    private int pinPasses;
    private float downY;
    private boolean dragging;

    FixedScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFillViewport(true);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void pin() {
        // While restoring after a content refresh, getScrollY() may already be
        // clamped to 0; do not overwrite the saved position with that.
        if (pinPasses > 0) {
            post(this::applyPin);
            return;
        }
        restoreTo(getScrollX(), getScrollY());
    }

    void restoreTo(int x, int y) {
        pinnedX = x;
        pinnedY = y;
        pinPasses = 4;
        post(this::applyPin);
    }

    void runKeepingScroll(Runnable mutation) {
        final int x = getScrollX();
        final int y = getScrollY();
        mutation.run();
        restoreTo(x, y);
    }

    private void applyPin() {
        if (pinPasses <= 0 || dragging) return;
        scrollTo(pinnedX, pinnedY);
        pinPasses--;
        if (pinPasses > 0) post(this::applyPin);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = false;
            downY = ev.getY();
            pin();
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging && Math.abs(ev.getY() - downY) > touchSlop) {
                dragging = true;
                pinPasses = 0;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                pinPasses = 0;
            } else {
                pin();
            }
            dragging = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (focused instanceof EditText) {
            super.requestChildFocus(child, focused);
            return;
        }
        pin();
        super.requestChildFocus(child, focused);
        applyPin();
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return child instanceof EditText
                && super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pinPasses > 0 && !dragging) {
            scrollTo(pinnedX, pinnedY);
        }
    }
}
