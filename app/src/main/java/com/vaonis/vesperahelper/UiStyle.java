package com.vaonis.vesperahelper;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** Raised 3D chrome for real buttons vs recessed chips for status text. */
final class UiStyle {
    /** Secondary actions: save, scan, verify, check, location. */
    static final int SLATE = 0xFF4A6F86;
    /** Unselected tabs and muted chrome. */
    static final int SLATE_MUTED = 0xFF7A8B96;
    /** Recessed well behind the tab strip. */
    static final int TAB_WELL = 0xFF9AA9B5;
    /** Unselected tab face (lighter than the well so segments stay distinct). */
    static final int TAB_IDLE_FACE = 0xFFF4F7FA;
    static final int TAB_IDLE_TEXT = 0xFF263238;
    static final int TAB_DIVIDER = 0xFF6D7D88;
    /** Connect / connected / mount. */
    static final int GREEN = 0xFF3B7F55;
    /** Instrument detected (status). */
    static final int AMBER = 0xFFC9A227;
    /** Restart / eject (warning, not destructive). */
    static final int TERRACOTTA = 0xFFC0724A;
    /** Stop observation (halt, not power-off). */
    static final int INK = 0xFF2A2D31;
    /** Disconnect / clear / unmount / power off. */
    static final int ROSE = 0xFFB05757;
    /** Offline / disabled. */
    static final int STEEL = 0xFF8A97A3;
    /** In-progress / connecting. */
    static final int STEEL_BLUE = 0xFF5A7A92;

    private UiStyle() {}

    static Drawable raisedButton(int color, float density) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed, android.R.attr.state_enabled },
                beveled(color, density, true, true));
        states.addState(new int[] { android.R.attr.state_enabled },
                beveled(color, density, true, false));
        states.addState(new int[] {},
                beveled(mix(color, 0xFF9E9E9E, 0.45f), density, false, false));
        return states;
    }

    static Drawable recessedStatus(int color, float density) {
        int radius = Math.round(6 * density);
        int inset = Math.max(2, Math.round(2.5f * density));
        GradientDrawable well = roundRect(darken(color, 0.38f), radius);
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] { color, darken(color, 0.10f) });
        fill.setCornerRadius(radius);
        InsetDrawable insetFill = new InsetDrawable(fill, 0, inset, 0, 0);
        return new LayerDrawable(new Drawable[] { well, insetFill });
    }

    static Drawable sectionPanel(float density) {
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xFFF3F7FA);
        box.setCornerRadius(Math.round(10 * density));
        box.setStroke(Math.max(2, Math.round(1.5f * density)), 0xFF90A4AE);
        return box;
    }

    static Drawable tabStripWell(float density) {
        return roundRect(TAB_WELL, Math.round(8 * density));
    }

    static void applyTab(TextView view, int color, boolean selected,
                         boolean roundLeft, boolean roundRight) {
        float density = view.getResources().getDisplayMetrics().density;
        view.setEnabled(true);
        view.setBackground(tabBackground(selected ? color : TAB_IDLE_FACE, density,
                roundLeft, roundRight, selected));
        view.setTextColor(selected ? textOn(color) : TAB_IDLE_TEXT);
        view.setStateListAnimator(null);
        view.setElevation(0f);
        int h = Math.round(12 * density);
        int v = Math.round(10 * density);
        view.setPadding(h, v, h, v);
        view.setAlpha(1f);
        view.setMinHeight(Math.round(44 * density));
        view.setMinimumHeight(Math.round(44 * density));
    }

    static void applyRaised(TextView view, int color, boolean enabled) {
        float density = view.getResources().getDisplayMetrics().density;
        view.setEnabled(enabled);
        view.setBackground(raisedButton(color, density));
        view.setTextColor(textOn(color));
        view.setStateListAnimator(null);
        view.setElevation(enabled ? 2.5f * density : 0f);
        int h = Math.round(16 * density);
        int v = Math.round(10 * density);
        int shadow = Math.max(2, Math.round(3 * density));
        view.setPadding(h, v, h, v + shadow);
        view.setAlpha(enabled ? 1f : 0.82f);
    }

    static void applyRecessed(TextView view, int color) {
        float density = view.getResources().getDisplayMetrics().density;
        view.setBackground(recessedStatus(color, density));
        view.setTextColor(textOn(color));
        view.setStateListAnimator(null);
        view.setElevation(0f);
        int h = Math.round(12 * density);
        int v = Math.round(8 * density);
        view.setPadding(h, v + Math.round(2 * density), h, v);
        view.setAlpha(1f);
    }

    static void spaceBelow(View view, float density) {
        if (!(view.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams)) {
            return;
        }
        android.widget.LinearLayout.LayoutParams lp =
                (android.widget.LinearLayout.LayoutParams) view.getLayoutParams();
        lp.bottomMargin = Math.max(lp.bottomMargin, Math.round(8 * density));
        view.setLayoutParams(lp);
    }

    static int textOn(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.62 ? 0xFF212121 : 0xFFFFFFFF;
    }

    private static Drawable tabBackground(int color, float density,
                                         boolean roundLeft, boolean roundRight,
                                         boolean selected) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed },
                tabFace(color, density, roundLeft, roundRight, true, selected));
        states.addState(new int[] {},
                tabFace(color, density, roundLeft, roundRight, false, selected));
        return states;
    }

    private static Drawable tabFace(int color, float density,
                                    boolean roundLeft, boolean roundRight,
                                    boolean pressed, boolean selected) {
        float radius = 7 * density;
        float left = roundLeft ? radius : 0f;
        float right = roundRight ? radius : 0f;
        float[] radii = new float[] {
                left, left, right, right, right, right, left, left
        };
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(pressed ? darken(color, 0.08f) : color);
        fill.setCornerRadii(radii);
        int stroke = Math.max(1, Math.round(density));
        fill.setStroke(stroke, selected ? darken(color, 0.18f) : TAB_DIVIDER);
        return fill;
    }

    private static Drawable beveled(int color, float density, boolean raised, boolean pressed) {
        int radius = Math.round(7 * density);
        int depth = Math.max(2, Math.round(2.5f * density));
        GradientDrawable shade = roundRect(darken(color, raised ? 0.26f : 0.14f), radius);
        GradientDrawable face = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                pressed
                        ? new int[] { darken(color, 0.08f), darken(color, 0.16f) }
                        : new int[] { lighten(color, 0.14f), color, darken(color, 0.08f) });
        face.setCornerRadius(radius);
        face.setStroke(Math.max(1, Math.round(density)),
                pressed ? darken(color, 0.12f) : mix(lighten(color, 0.22f), 0xFFFFFFFF, 0.15f));
        InsetDrawable insetFace = pressed
                ? new InsetDrawable(face, 0, depth, 0, Math.max(1, depth / 3))
                : new InsetDrawable(face, 0, 0, 0, raised ? depth : Math.max(1, depth / 2));
        return new LayerDrawable(new Drawable[] { shade, insetFace });
    }

    private static GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int lighten(int color, float amount) {
        return mix(color, 0xFFFFFFFF, amount);
    }

    private static int darken(int color, float amount) {
        return mix(color, 0xFF000000, amount);
    }

    private static int mix(int a, int b, float t) {
        float u = 1f - t;
        int aA = (a >>> 24) & 0xFF;
        int bA = (b >>> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int aB = a & 0xFF;
        int bB = b & 0xFF;
        return ((Math.round(aA * u + bA * t) & 0xFF) << 24)
                | ((Math.round(aR * u + bR * t) & 0xFF) << 16)
                | ((Math.round(aG * u + bG * t) & 0xFF) << 8)
                | (Math.round(aB * u + bB * t) & 0xFF);
    }
}
