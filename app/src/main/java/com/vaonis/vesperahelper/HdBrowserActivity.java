package com.vaonis.vesperahelper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone Home-screen activity: browse the mounted USB HD and swipe photos.
 * Does not open Helper tabs.
 */
public final class HdBrowserActivity extends Activity {
    private static final String STATE_PATH = "path";
    private static final int COLOR_BG = 0xFFF4F7FA;
    private static final int COLOR_HEADER = 0xFF1C4A5C;
    private static final int COLOR_ROW = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF5A6A74;
    private static final int THUMB_PX = 128;
    private static final int VIEWER_MAX = 2048;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService decodePool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(96);
    private final AtomicInteger listGeneration = new AtomicInteger();

    private File rootDir;
    private File currentDir;
    private final List<Item> items = new ArrayList<>();
    private final List<Item> images = new ArrayList<>();

    private TextView titleView;
    private TextView subtitleView;
    private Button upButton;
    private ListView listView;
    private FileAdapter adapter;
    private FrameLayout viewer;
    private ImageView viewerImage;
    private TextView viewerCaption;
    private int viewerIndex = -1;
    private Bitmap viewerBitmap;
    private float downX;
    private float downY;
    private long downAt;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.hd_browser_shortcut_label);
        buildUi();
        PhotoSyncService.ensure(this);
        String restored = state == null ? null : state.getString(STATE_PATH);
        worker.execute(() -> {
            DaemonDisk.ensureBind(this);
            File root = DaemonDisk.photosDir(this);
            File start = root;
            if (restored != null) {
                File candidate = new File(restored);
                if (isUnderRoot(candidate, root) && candidate.isDirectory()) start = candidate;
            }
            File open = start;
            mainHandler.post(() -> {
                rootDir = root;
                openDir(open);
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (currentDir != null) out.putString(STATE_PATH, currentDir.getAbsolutePath());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        decodePool.shutdownNow();
        recycleViewer();
        thumbs.evictAll();
    }

    @Override public void onBackPressed() {
        if (viewer.getVisibility() == View.VISIBLE) {
            closeViewer();
            return;
        }
        if (goUp()) return;
        super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (viewer.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                showViewer(viewerIndex - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                showViewer(viewerIndex + 1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(COLOR_HEADER);
        header.setPadding(pad, pad, pad, pad);

        titleView = new TextView(this);
        titleView.setText(R.string.hd_browser_title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);

        subtitleView = new TextView(this);
        subtitleView.setText(R.string.hd_browser_loading);
        subtitleView.setTextColor(0xFFD5E6EC);
        subtitleView.setTextSize(13);
        subtitleView.setPadding(0, Math.round(4 * density), 0, 0);

        header.addView(titleView);
        header.addView(subtitleView);

        upButton = new Button(this);
        upButton.setAllCaps(false);
        upButton.setText(R.string.hd_browser_up);
        UiStyle.applyRaised(upButton, UiStyle.SLATE, true);
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upLp.setMargins(pad, pad, pad, 0);
        upButton.setLayoutParams(upLp);
        upButton.setOnClickListener(v -> goUp());
        upButton.setVisibility(View.GONE);

        adapter = new FileAdapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(Math.round(6 * density));
        listView.setPadding(pad, pad, pad, pad);
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            onItem(items.get(position));
        });

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(upButton);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        viewerImage = new ImageView(this);
        viewerImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewerImage.setBackgroundColor(0xFF101418);
        viewerCaption = new TextView(this);
        viewerCaption.setTextColor(0xFFFFFFFF);
        viewerCaption.setTextSize(14);
        viewerCaption.setGravity(Gravity.CENTER);
        viewerCaption.setBackgroundColor(0x99000000);
        int capPad = Math.round(10 * density);
        viewerCaption.setPadding(capPad, capPad, capPad, capPad);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);

        viewer = new FrameLayout(this);
        viewer.addView(viewerImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewer.addView(viewerCaption, capLp);
        viewer.setVisibility(View.GONE);
        viewer.setClickable(true);
        viewer.setOnTouchListener((v, event) -> handleViewerTouch(event));

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(viewer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(wrap);
    }

    private void openDir(File dir) {
        if (dir == null) return;
        currentDir = dir;
        titleView.setText(displayPath(dir));
        subtitleView.setText(R.string.hd_browser_loading);
        upButton.setVisibility(isRoot(dir) ? View.GONE : View.VISIBLE);
        final int gen = listGeneration.incrementAndGet();
        worker.execute(() -> {
            final List<Item> listed = listItems(dir);
            mainHandler.post(() -> {
                if (gen != listGeneration.get() || isFinishing()) return;
                items.clear();
                items.addAll(listed);
                images.clear();
                for (Item item : listed) {
                    if (item.image) images.add(item);
                }
                adapter.notifyDataSetChanged();
                listView.setSelection(0);
                if (!DaemonDisk.isPhotosBoundLive(rootDir)) {
                    subtitleView.setText(R.string.hd_browser_unmounted);
                } else if (listed.isEmpty()) {
                    subtitleView.setText(R.string.hd_browser_empty);
                } else {
                    subtitleView.setText(getString(R.string.hd_browser_items, listed.size()));
                }
            });
        });
    }

    private boolean goUp() {
        if (currentDir == null || rootDir == null || isRoot(currentDir)) return false;
        File parent = currentDir.getParentFile();
        if (parent == null || !isUnderRoot(parent, rootDir)) {
            parent = rootDir;
        }
        openDir(parent);
        return true;
    }

    private void onItem(Item item) {
        if (item.directory) {
            openDir(item.file);
            return;
        }
        if (item.image) {
            int index = 0;
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).file.equals(item.file)) {
                    index = i;
                    break;
                }
            }
            showViewer(index);
            return;
        }
        subtitleView.setText(getString(R.string.hd_browser_not_preview, item.name));
    }

    private void showViewer(int index) {
        if (images.isEmpty()) return;
        int wrapped = index % images.size();
        if (wrapped < 0) wrapped += images.size();
        viewerIndex = wrapped;
        Item item = images.get(wrapped);
        viewer.setVisibility(View.VISIBLE);
        viewerCaption.setText(getString(R.string.hd_browser_image_of,
                wrapped + 1, images.size()) + "  ·  " + item.name
                + "  ·  " + PhotoSyncEngine.formatBytes(item.size));
        viewerImage.setImageBitmap(null);
        final File file = item.file;
        final int expected = wrapped;
        decodePool.execute(() -> {
            Bitmap bitmap = decodeScaled(file, VIEWER_MAX);
            mainHandler.post(() -> {
                if (viewerIndex != expected || viewer.getVisibility() != View.VISIBLE) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                recycleViewer();
                viewerBitmap = bitmap;
                if (bitmap == null) {
                    viewerImage.setImageDrawable(null);
                    viewerCaption.setText(getString(R.string.hd_browser_not_preview, file.getName()));
                } else {
                    viewerImage.setImageBitmap(bitmap);
                }
            });
        });
    }

    private void closeViewer() {
        viewer.setVisibility(View.GONE);
        recycleViewer();
        viewerIndex = -1;
    }

    private void recycleViewer() {
        viewerImage.setImageBitmap(null);
        if (viewerBitmap != null) {
            viewerBitmap.recycle();
            viewerBitmap = null;
        }
    }

    private boolean handleViewerTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downAt = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                float slop = 48 * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    showViewer(viewerIndex + (dx < 0 ? 1 : -1));
                    return true;
                }
                if (Math.abs(dx) < slop && Math.abs(dy) < slop
                        && System.currentTimeMillis() - downAt < 350) {
                    closeViewer();
                    return true;
                }
                return true;
            default:
                return true;
        }
    }

    private List<Item> listItems(File dir) {
        List<Item> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (hidden(file)) continue;
            out.add(Item.from(file));
        }
        Collections.sort(out, ITEM_ORDER);
        return out;
    }

    private String displayPath(File dir) {
        if (dir == null || rootDir == null) return getString(R.string.hd_browser_root);
        String full;
        String base;
        try {
            full = dir.getCanonicalPath();
            base = rootDir.getCanonicalPath();
        } catch (Exception ignored) {
            full = dir.getAbsolutePath();
            base = rootDir.getAbsolutePath();
        }
        if (full.equals(base)) return getString(R.string.hd_browser_root);
        if (full.startsWith(base + File.separator)) {
            return getString(R.string.hd_browser_root) + " / "
                    + full.substring(base.length() + 1).replace(File.separatorChar, '/');
        }
        return dir.getName();
    }

    private boolean isRoot(File dir) {
        return dir != null && rootDir != null && sameFile(dir, rootDir);
    }

    private static boolean isUnderRoot(File file, File root) {
        if (file == null || root == null) return false;
        try {
            String path = file.getCanonicalPath();
            String base = root.getCanonicalPath();
            return path.equals(base) || path.startsWith(base + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Exception ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    private static boolean hidden(File file) {
        String name = file.getName();
        if (name.isEmpty() || name.charAt(0) == '.') return true;
        String upper = name.toUpperCase(Locale.US);
        return "$RECYCLE.BIN".equals(upper)
                || "SYSTEM VOLUME INFORMATION".equals(upper)
                || "RECYCLER".equals(upper);
    }

    private static Bitmap decodeScaled(File file, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    private static final Comparator<Item> ITEM_ORDER = (a, b) -> {
        if (a.directory != b.directory) return a.directory ? -1 : 1;
        return a.name.compareToIgnoreCase(b.name);
    };

    private static final class Item {
        final File file;
        final String name;
        final boolean directory;
        final boolean image;
        final long size;

        Item(File file, String name, boolean directory, boolean image, long size) {
            this.file = file;
            this.name = name;
            this.directory = directory;
            this.image = image;
            this.size = size;
        }

        static Item from(File file) {
            boolean dir = file.isDirectory();
            return new Item(file, file.getName(), dir, !dir && isImageName(file.getName()),
                    dir ? 0L : file.length());
        }
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            float density = getResources().getDisplayMetrics().density;
            Holder holder;
            if (convertView instanceof LinearLayout
                    && convertView.getTag() instanceof Holder) {
                holder = (Holder) convertView.getTag();
            } else {
                holder = new Holder();
                LinearLayout row = new LinearLayout(HdBrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int hPad = Math.round(12 * density);
                int vPad = Math.round(8 * density);
                row.setPadding(hPad, vPad, hPad, vPad);
                row.setBackgroundColor(COLOR_ROW);
                row.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                holder.thumb = new ImageView(HdBrowserActivity.this);
                int thumb = Math.round(48 * density);
                LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumb, thumb);
                thumbLp.setMarginEnd(Math.round(12 * density));
                holder.thumb.setLayoutParams(thumbLp);
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.thumb.setBackgroundColor(0xFFE3EAF0);

                LinearLayout textCol = new LinearLayout(HdBrowserActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                holder.name = new TextView(HdBrowserActivity.this);
                holder.name.setTextSize(16);
                holder.name.setTextColor(0xFF12222A);
                holder.meta = new TextView(HdBrowserActivity.this);
                holder.meta.setTextSize(12);
                holder.meta.setTextColor(COLOR_MUTED);
                textCol.addView(holder.name);
                textCol.addView(holder.meta);

                row.addView(holder.thumb);
                row.addView(textCol);
                row.setTag(holder);
                holder.row = row;
                convertView = row;
            }

            Item item = items.get(position);
            holder.seq++;
            final int seq = holder.seq;
            holder.name.setText(item.name);
            holder.thumb.setImageBitmap(null);
            if (item.directory) {
                holder.meta.setText(R.string.hd_browser_folder);
                holder.thumb.setBackgroundColor(0xFF3B7F55);
            } else if (item.image) {
                holder.meta.setText(PhotoSyncEngine.formatBytes(item.size));
                holder.thumb.setBackgroundColor(0xFF4A6F86);
                Bitmap cached = thumbs.get(item.file.getAbsolutePath());
                if (cached != null && !cached.isRecycled()) {
                    holder.thumb.setImageBitmap(cached);
                } else {
                    final File file = item.file;
                    decodePool.execute(() -> {
                        Bitmap bitmap = decodeScaled(file, THUMB_PX);
                        if (bitmap != null) thumbs.put(file.getAbsolutePath(), bitmap);
                        mainHandler.post(() -> {
                            if (seq != holder.seq) return;
                            if (bitmap != null) holder.thumb.setImageBitmap(bitmap);
                        });
                    });
                }
            } else {
                holder.meta.setText(PhotoSyncEngine.formatBytes(item.size));
                holder.thumb.setBackgroundColor(0xFF8A97A3);
            }
            return holder.row;
        }
    }

    private static final class Holder {
        LinearLayout row;
        ImageView thumb;
        TextView name;
        TextView meta;
        int seq;
    }
}
