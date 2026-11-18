package com.pixelcontrols.photo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.ExifInterface;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * "DSLR": open any photo from Google Photos (Share → DSLR, or Edit → Edit in… → DSLR)
 * and get a copy rendered as if it had been shot with the right settings. The original is
 * never modified; the result is saved to Pictures/PixelControls with the original's EXIF.
 */
public class EnhanceActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_PERM = 2;
    /** Set by the DSLR tile: open the newest camera photo and save the edit automatically. */
    public static final String EXTRA_LATEST = "latest";
    private boolean autoSave;
    private static final int PREVIEW_MAX = 1600;
    private static final long MAX_PIXELS = 16_000_000L;   // ~16 MP keeps memory sane on 50 MP shots

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView image;
    private TextView status;
    private SeekBar strength;
    private Button save, share;
    private ProgressBar progress;

    private Uri source;
    private Bitmap fullOriginal;          // decoded, rotated, full resolution
    private int[] previewOriginal;        // downscaled original pixels
    private int pw, ph;
    private Bitmap previewOriginalBmp, previewEnhancedBmp;
    private PhotoEnhancer.Params params;
    private Uri savedUri;
    private float savedStrength = -1;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (getIntent().getBooleanExtra(EXTRA_LATEST, false)) {
            autoSave = true;
            openLatest();
            return;
        }
        source = sourceUri(getIntent());
        if (source == null) {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Choose a photo"), REQ_PICK);
        } else {
            load();
        }
    }

    // ------------------------------------------------------------------ latest photo (tile)
    private String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean canReadPhotos() {
        if (checkSelfPermission(readPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        // Android 14+: "Allow limited access" still lets us read the photos the user picked.
        return Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void openLatest() {
        if (!canReadPhotos()) {
            status.setText("DSLR needs access to your photos to edit the one you just took.");
            if (Build.VERSION.SDK_INT >= 34)
                requestPermissions(new String[]{readPermission(), "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"}, REQ_PERM);
            else
                requestPermissions(new String[]{readPermission()}, REQ_PERM);
            return;
        }
        source = latestPhoto();
        if (source == null) {
            fail("No photos found. Take a photo first, or share one to DSLR from your gallery.");
            return;
        }
        load();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (canReadPhotos()) {
            openLatest();
        } else {
            Toast.makeText(this, "Without photo access, pick the photo yourself", Toast.LENGTH_LONG).show();
            autoSave = false;
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Choose a photo"), REQ_PICK);
        }
    }

    /** Newest camera photo; otherwise the newest photo that isn't a screenshot or one of our edits. */
    private Uri latestPhoto() {
        Uri coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] proj = {MediaStore.Images.Media._ID};
        String notOurs = MediaStore.Images.Media.RELATIVE_PATH + " NOT LIKE 'Pictures/PixelControls%' AND "
                + MediaStore.Images.Media.RELATIVE_PATH + " NOT LIKE '%Screenshots%'";
        String[] selections = {
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE 'DCIM/%' AND " + notOurs,
                notOurs,
        };
        for (String sel : selections) {
            try (Cursor c = getContentResolver().query(coll, proj, sel, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(coll, c.getLong(0));
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Uri sourceUri(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            return Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return i.getData();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                source = data.getData();
                load();
            } else {
                finish();
            }
        }
    }

    // ------------------------------------------------------------------ UI
    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        int pad = dp(12);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("Enhanced photo. Touch and hold to see the original.");
        image.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (previewOriginalBmp == null || previewEnhancedBmp == null) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { image.setImageBitmap(previewOriginalBmp); status.setText("Original"); }
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    image.setImageBitmap(previewEnhancedBmp); showParams();
                }
                return true;
            }
        });
        root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(pad, pad, pad, dp(4));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("Analyzing photo…");
        root.addView(status);

        TextView hint = new TextView(this);
        hint.setTextColor(0xFFAAAAAA);
        hint.setPadding(pad, 0, pad, dp(4));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setText("Touch and hold the photo to compare with the original · Strength:");
        root.addView(hint);

        strength = new SeekBar(this);
        strength.setMax(100);
        strength.setProgress(100);
        strength.setPadding(dp(20), dp(8), dp(20), dp(8));
        strength.setEnabled(false);
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) { if (fromUser) renderPreview(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { renderPreview(); }
        });
        root.addView(strength);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(pad, 0, pad, pad);
        save = new Button(this);
        save.setText("Save copy");
        save.setEnabled(false);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveAsync(false); } });
        share = new Button(this);
        share.setText("Share");
        share.setEnabled(false);
        share.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveAsync(true); } });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bl.setMargins(dp(4), 0, dp(4), 0);
        buttons.addView(save, bl);
        buttons.addView(share, new LinearLayout.LayoutParams(bl));
        root.addView(buttons);

        setContentView(root);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private float strengthValue() { return strength.getProgress() / 100f; }

    private void showParams() {
        if (params != null) status.setText(params.scaled(strengthValue()).describe());
    }

    private void fail(String msg) {
        main.post(new Runnable() { @Override public void run() {
            progress.setVisibility(View.GONE);
            status.setText(msg);
            Toast.makeText(EnhanceActivity.this, msg, Toast.LENGTH_LONG).show();
        } });
    }

    // ------------------------------------------------------------------ load + analyze
    private void load() {
        busy = true;
        new Thread(new Runnable() { @Override public void run() {
            try {
                Bitmap bmp = decode(source);
                if (bmp == null) { fail("Couldn't open this photo."); return; }
                fullOriginal = bmp;

                // Face check on a small copy; analysis on the full-resolution pixels (it samples
                // sparsely, so it's fast, and noise/sharpening are judged at true pixel level).
                int[] faces = faceRects(bmp);
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] all = new int[w * h];
                bmp.getPixels(all, 0, w, 0, 0, w, h);
                params = PhotoEnhancer.analyze(all, w, h, faces);
                all = null;

                float ps = Math.min(1f, PREVIEW_MAX / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
                Bitmap prev = ps < 1 ? Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * ps)),
                        Math.max(1, Math.round(bmp.getHeight() * ps)), true) : bmp.copy(Bitmap.Config.ARGB_8888, false);
                pw = prev.getWidth();
                ph = prev.getHeight();
                previewOriginal = new int[pw * ph];
                prev.getPixels(previewOriginal, 0, pw, 0, 0, pw, ph);
                previewOriginalBmp = prev;

                main.post(new Runnable() { @Override public void run() {
                    busy = false;
                    strength.setEnabled(true);
                    save.setEnabled(true);
                    share.setEnabled(true);
                    renderPreview();
                    if (autoSave) {           // from the tile: save the edit right away
                        autoSave = false;
                        saveAsync(false);
                    }
                } });
            } catch (OutOfMemoryError e) {
                fail("This photo is too large to process.");
            } catch (Exception e) {
                fail("Couldn't open this photo: " + e.getMessage());
            }
        } }, "enhance-load").start();
    }

    private Bitmap decode(Uri uri) throws IOException {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while ((long) (o.outWidth / sample) * (o.outHeight / sample) > MAX_PIXELS) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        d.inMutable = false;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, d); }
        if (bmp == null) return null;

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = cr.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { }
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return bmp;
        }
        Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (r != bmp) bmp.recycle();
        return r;
    }

    /**
     * Face boxes {x, y, w, h, ...} in src pixel coordinates for faces that are a meaningful part
     * of the frame (portrait mode + face metering), or null when there are none.
     */
    private static int[] faceRects(Bitmap src) {
        try {
            int w = 640 & ~1;
            float scale = w / (float) src.getWidth();
            int h = Math.max(2, Math.round(src.getHeight() * scale)) & ~1;
            Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
            Bitmap rgb565 = scaled.copy(Bitmap.Config.RGB_565, false);
            if (scaled != src) scaled.recycle();
            FaceDetector.Face[] faces = new FaceDetector.Face[4];
            int n = new FaceDetector(w, h, faces.length).findFaces(rgb565, faces);
            rgb565.recycle();
            int[] out = new int[n * 4];
            int k = 0;
            PointF mid = new PointF();
            for (int i = 0; i < n; i++) {
                FaceDetector.Face f = faces[i];
                if (f == null || f.confidence() < 0.4f || f.eyesDistance() < 0.05f * Math.min(w, h)) continue;
                f.getMidPoint(mid);
                float e = f.eyesDistance();
                // A face is roughly 2.2 eye-distances wide and 2.8 tall, centred a bit below the eyes.
                float fw = 2.2f * e, fh = 2.8f * e;
                out[k++] = Math.round((mid.x - fw / 2) / scale);
                out[k++] = Math.round((mid.y - fh * 0.4f) / scale);
                out[k++] = Math.round(fw / scale);
                out[k++] = Math.round(fh / scale);
            }
            return k == 0 ? null : java.util.Arrays.copyOf(out, k);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ preview
    private void renderPreview() {
        if (params == null || previewOriginal == null) return;
        final PhotoEnhancer.Params p = params.scaled(strengthValue());
        new Thread(new Runnable() { @Override public void run() {
            int[] px = previewOriginal.clone();
            PhotoEnhancer.apply(px, pw, ph, p);
            Bitmap b = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888);
            main.post(new Runnable() { @Override public void run() {
                progress.setVisibility(View.GONE);
                previewEnhancedBmp = b;
                image.setImageBitmap(b);
                showParams();
            } });
        } }, "enhance-preview").start();
    }

    // ------------------------------------------------------------------ save
    private void saveAsync(final boolean thenShare) {
        if (busy || fullOriginal == null) return;
        final float s = strengthValue();
        if (savedUri != null && s == savedStrength) {   // already saved at this strength
            if (thenShare) shareUri(savedUri);
            else Toast.makeText(this, "Already saved to Pictures/PixelControls", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        save.setEnabled(false);
        share.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Rendering full resolution…");
        new Thread(new Runnable() { @Override public void run() {
            Uri out = null;
            try {
                int w = fullOriginal.getWidth(), h = fullOriginal.getHeight();
                int[] px = new int[w * h];
                fullOriginal.getPixels(px, 0, w, 0, 0, w, h);
                PhotoEnhancer.apply(px, w, h, params.scaled(s));
                Bitmap result = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
                px = null;
                out = writeToGallery(result);
                result.recycle();
                savedUri = out;
                savedStrength = s;
            } catch (OutOfMemoryError e) {
                fail("Not enough memory to save at full size.");
            } catch (Exception e) {
                fail("Save failed: " + e.getMessage());
            }
            final Uri done = out;
            main.post(new Runnable() { @Override public void run() {
                busy = false;
                save.setEnabled(true);
                share.setEnabled(true);
                progress.setVisibility(View.GONE);
                if (done != null) {
                    showParams();
                    if (thenShare) shareUri(done);
                    else Toast.makeText(EnhanceActivity.this, "Saved to Pictures/PixelControls", Toast.LENGTH_SHORT).show();
                    if (!thenShare) status.setText("Saved ✓  Settings chosen for this photo: " + params.scaled(s).describe());
                }
            } });
        } }, "enhance-save").start();
    }

    private Uri writeToGallery(Bitmap bmp) throws IOException {
        ContentResolver cr = getContentResolver();
        String base = displayName(source);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, base + "_pro.jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelControls");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        long taken = dateTaken(source);
        if (taken > 0) v.put(MediaStore.Images.Media.DATE_TAKEN, taken);
        Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("couldn't create the file");
        try {
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) throw new IOException("JPEG encode failed");
            }
            copyExif(source, uri);
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e;
        }
    }

    private static final String[] EXIF_TAGS = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensMake", "LensModel",
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, "PhotographicSensitivity", ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    };

    private void copyExif(Uri from, Uri to) {
        try (InputStream in = getContentResolver().openInputStream(from);
             ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(to, "rw")) {
            if (in == null || pfd == null) return;
            ExifInterface src = new ExifInterface(in);
            ExifInterface dst = new ExifInterface(pfd.getFileDescriptor());
            for (String tag : EXIF_TAGS) {
                String val = src.getAttribute(tag);
                if (val != null) dst.setAttribute(tag, val);
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "PixelControls DSLR");
            dst.saveAttributes();
        } catch (Exception ignored) {
            // Metadata is nice-to-have; the photo itself is already written.
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        return "IMG_" + System.currentTimeMillis();
    }

    private long dateTaken(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Images.Media.DATE_TAKEN}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        return 0;
    }

    private void shareUri(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share enhanced photo"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fullOriginal != null && !busy) fullOriginal.recycle();
    }
}
