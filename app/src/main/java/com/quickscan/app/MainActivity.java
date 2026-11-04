package com.quickscan.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Size;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_CAM = 11;
    private static final int REQ_PICK = 12;

    private final ArrayList<Uri> images = new ArrayList<>();
    private final HashMap<String, Bitmap> cache = new HashMap<>();
    private Uri pendingUri, lastPdf;

    private EditText folderEt, pdfEt, sizeEt;
    private Spinner unitSp;
    private CheckBox contBox;
    private LinearLayout thumbs, resultRow;
    private TextView countTv, status;
    private Button pdfBtn;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(root);

        root.addView(label("1. Folder name"));
        folderEt = new EditText(this);
        folderEt.setHint("e.g. Aadhaar_Docs");
        folderEt.setSingleLine(true);
        root.addView(folderEt, mw());
        root.addView(small("Photos -> Pictures/<folder>   |   PDF -> Documents/<folder>"));

        root.addView(label("2. Add photos"));
        root.addView(btn("Take photo", v -> takePhoto()), mw());
        contBox = new CheckBox(this);
        contBox.setText("Keep camera open after each photo (quick scan)");
        root.addView(contBox, mw());
        root.addView(btn("Add from gallery", v -> pickGallery()), mw());

        countTv = new TextView(this);
        countTv.setPadding(0, dp(10), 0, dp(4));
        root.addView(countTv, mw());
        HorizontalScrollView hs = new HorizontalScrollView(this);
        thumbs = new LinearLayout(this);
        thumbs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(thumbs);
        root.addView(hs, mw());
        root.addView(small("Long-press a thumbnail to remove it from the list"));
        root.addView(btn("New set (clear list)", v -> {
            images.clear();
            refresh();
        }), mw());

        root.addView(label("3. PDF name"));
        pdfEt = new EditText(this);
        pdfEt.setHint("leave empty to use folder name");
        pdfEt.setSingleLine(true);
        root.addView(pdfEt, mw());

        root.addView(label("4. Maximum PDF size (empty = no limit)"));
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeEt = new EditText(this);
        sizeEt.setHint("e.g. 200");
        sizeEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        sizeRow.addView(sizeEt, new LinearLayout.LayoutParams(0, -2, 1f));
        unitSp = new Spinner(this);
        unitSp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"KB", "MB"}));
        sizeRow.addView(unitSp, new LinearLayout.LayoutParams(-2, -2));
        root.addView(sizeRow, mw());

        HorizontalScrollView ps = new HorizontalScrollView(this);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(preset("100 KB", "100", 0));
        presets.addView(preset("200 KB", "200", 0));
        presets.addView(preset("500 KB", "500", 0));
        presets.addView(preset("1 MB", "1", 1));
        presets.addView(preset("2 MB", "2", 1));
        ps.addView(presets);
        root.addView(ps, mw());

        pdfBtn = btn("Create PDF", v -> makePdf());
        root.addView(pdfBtn, mw());

        status = new TextView(this);
        status.setPadding(0, dp(10), 0, dp(4));
        root.addView(status, mw());

        resultRow = new LinearLayout(this);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        resultRow.addView(btn("Open PDF", v -> openPdf()), new LinearLayout.LayoutParams(0, -2, 1f));
        resultRow.addView(btn("Share PDF", v -> sharePdf()), new LinearLayout.LayoutParams(0, -2, 1f));
        resultRow.setVisibility(View.GONE);
        root.addView(resultRow, mw());

        if (saved != null) {
            ArrayList<String> s = saved.getStringArrayList("imgs");
            if (s != null) for (String x : s) images.add(Uri.parse(x));
            String p = saved.getString("pending");
            if (p != null) pendingUri = Uri.parse(p);
        }
        refresh();
        setContentView(sv);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> s = new ArrayList<>();
        for (Uri u : images) s.add(u.toString());
        out.putStringArrayList("imgs", s);
        if (pendingUri != null) out.putString("pending", pendingUri.toString());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(4));
        return tv;
    }

    private TextView small(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(12);
        tv.setTextColor(0xFF666666);
        return tv;
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button preset(String t, final String val, final int unit) {
        Button b = btn(t, v -> {
            sizeEt.setText(val);
            unitSp.setSelection(unit);
        });
        b.setTextSize(12);
        return b;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? null : s;
    }

    private static String fmt(long b) {
        if (b >= 1024L * 1024L) return String.format(Locale.US, "%.2f MB", b / 1048576.0);
        return String.format(Locale.US, "%.1f KB", b / 1024.0);
    }

    private void refresh() {
        countTv.setText(images.size() + " photo(s) in this set");
        thumbs.removeAllViews();
        for (int i = 0; i < images.size(); i++) {
            final Uri u = images.get(i);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFFDDDDDD);
            Bitmap t = thumb(u);
            if (t != null) iv.setImageBitmap(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(80));
            lp.setMargins(0, 0, dp(6), 0);
            iv.setLayoutParams(lp);
            iv.setOnLongClickListener(v -> {
                images.remove(u);
                refresh();
                return true;
            });
            thumbs.addView(iv);
        }
    }

    private Bitmap thumb(Uri u) {
        Bitmap b = cache.get(u.toString());
        if (b != null) return b;
        try {
            b = getContentResolver().loadThumbnail(u, new Size(200, 200), null);
        } catch (Exception e) {
            b = null;
        }
        if (b == null) {
            try {
                b = loadOriented(u, 200);
            } catch (Exception e) {
                b = null;
            }
        }
        if (b != null) cache.put(u.toString(), b);
        return b;
    }

    // ---------------------------------------------------------------- Camera / gallery

    private void takePhoto() {
        String folder = clean(folderEt.getText().toString());
        if (folder == null) {
            toast("Enter a folder name first");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, folder + "_" + ts + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + folder);
        pendingUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (pendingUri == null) {
            toast("Cannot create photo file");
            return;
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, pendingUri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAM);
        } catch (Exception e) {
            try {
                getContentResolver().delete(pendingUri, null, null);
            } catch (Exception ignored) {
            }
            pendingUri = null;
            toast("No camera app found");
        }
    }

    private void pickGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Select images"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAM) {
            boolean ok = res == RESULT_OK && pendingUri != null;
            if (ok) {
                images.add(pendingUri);
                refresh();
            } else if (pendingUri != null) {
                try {
                    getContentResolver().delete(pendingUri, null, null);
                } catch (Exception ignored) {
                }
            }
            pendingUri = null;
            if (ok && contBox.isChecked()) takePhoto();
        } else if (req == REQ_PICK && res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) images.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                images.add(data.getData());
            }
            refresh();
        }
    }

    // ---------------------------------------------------------------- PDF

    private void makePdf() {
        if (images.isEmpty()) {
            toast("Add at least one photo");
            return;
        }
        final String folder = clean(folderEt.getText().toString());
        if (folder == null) {
            toast("Enter a folder name");
            return;
        }
        String nm = clean(pdfEt.getText().toString());
        if (nm == null) nm = folder;
        if (nm.toLowerCase(Locale.ROOT).endsWith(".pdf")) nm = nm.substring(0, nm.length() - 4);
        final String fileName = nm + ".pdf";

        long limit = 0;
        String s = sizeEt.getText().toString().trim();
        if (!s.isEmpty()) {
            try {
                double val = Double.parseDouble(s);
                long mult = unitSp.getSelectedItemPosition() == 0 ? 1024L : 1024L * 1024L;
                limit = (long) (val * mult);
            } catch (NumberFormatException e) {
                toast("Invalid size");
                return;
            }
        }
        final long lim = limit;
        final ArrayList<Uri> list = new ArrayList<>(images);

        pdfBtn.setEnabled(false);
        resultRow.setVisibility(View.GONE);
        status.setText("Working...");

        new Thread(() -> {
            String msg;
            boolean ok = true;
            try {
                msg = buildPdf(list, folder, fileName, lim);
            } catch (Throwable t) {
                ok = false;
                msg = "Failed: " + t;
            }
            final String m = msg;
            final boolean good = ok;
            runOnUiThread(() -> {
                status.setText(m);
                pdfBtn.setEnabled(true);
                if (good) resultRow.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private static class Jpg {
        byte[] data;
        int w, h;
    }

    private static class CountOut extends OutputStream {
        final OutputStream o;
        long n = 0;

        CountOut(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            n++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            n += len;
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private String buildPdf(List<Uri> list, String folder, String fileName, long limit) throws Exception {
        final int n = list.size();
        long perPage = 0;
        if (limit > 0) perPage = Math.max(2000L, (limit - 600L - 600L * n) / n);

        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/" + folder);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Files.getContentUri("external"), cv);
        if (uri == null) throw new IOException("Cannot create PDF file");

        long size = 0;
        try {
            try (OutputStream os = cr.openOutputStream(uri)) {
                CountOut out = new CountOut(new BufferedOutputStream(os, 65536));
                ArrayList<Long> offs = new ArrayList<>();

                out.write(ascii("%PDF-1.4\n"));
                offs.add(out.n);
                out.write(ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"));

                StringBuilder kids = new StringBuilder();
                for (int k = 0; k < n; k++) kids.append(3 + 3 * k).append(" 0 R ");
                offs.add(out.n);
                out.write(ascii("2 0 obj\n<< /Type /Pages /Count " + n + " /Kids [ " + kids + "] >>\nendobj\n"));

                for (int k = 0; k < n; k++) {
                    final int kk = k;
                    runOnUiThread(() -> status.setText("Processing image " + (kk + 1) + " of " + n + "..."));

                    Bitmap src = loadOriented(list.get(k), 2480);
                    boolean land = src.getWidth() > src.getHeight();
                    float pw = land ? 842f : 595f;
                    float ph = land ? 595f : 842f;
                    float m = 14f;
                    float sc = Math.min((pw - 2 * m) / src.getWidth(), (ph - 2 * m) / src.getHeight());
                    float dw = src.getWidth() * sc;
                    float dh = src.getHeight() * sc;
                    float x = (pw - dw) / 2f;
                    float y = (ph - dh) / 2f;

                    Jpg j = fit(src, perPage);
                    src.recycle();

                    int po = 3 + 3 * k;
                    byte[] cb = ascii(String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", dw, dh, x, y));

                    offs.add(out.n);
                    out.write(ascii(String.format(Locale.US,
                            "%d 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] "
                                    + "/Resources << /XObject << /Im0 %d 0 R >> >> /Contents %d 0 R >>\nendobj\n",
                            po, pw, ph, po + 2, po + 1)));

                    offs.add(out.n);
                    out.write(ascii((po + 1) + " 0 obj\n<< /Length " + cb.length + " >>\nstream\n"));
                    out.write(cb);
                    out.write(ascii("\nendstream\nendobj\n"));

                    offs.add(out.n);
                    out.write(ascii((po + 2) + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + j.w
                            + " /Height " + j.h + " /ColorSpace /DeviceRGB /BitsPerComponent 8"
                            + " /Filter /DCTDecode /Length " + j.data.length + " >>\nstream\n"));
                    out.write(j.data);
                    out.write(ascii("\nendstream\nendobj\n"));
                }

                long xref = out.n;
                int total = offs.size() + 1;
                out.write(ascii("xref\n0 " + total + "\n0000000000 65535 f \n"));
                for (Long off : offs) {
                    out.write(ascii(String.format(Locale.US, "%010d 00000 n \n", off)));
                }
                out.write(ascii("trailer\n<< /Size " + total + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
                out.flush();
                size = out.n;
            }
            ContentValues d = new ContentValues();
            d.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, d, null, null);
        } catch (Exception e) {
            try {
                cr.delete(uri, null, null);
            } catch (Exception ignored) {
            }
            throw e;
        }

        lastPdf = uri;
        String msg = "Saved " + n + " page(s)\nDocuments/" + folder + "/" + fileName
                + "\nSize: " + fmt(size);
        if (limit > 0) msg += "  (limit " + fmt(limit) + ")";
        if (limit > 0 && size > limit) msg += "\nCould not go below this size with " + n + " page(s)";
        return msg;
    }

    /** Compress one bitmap to JPEG, reducing quality then resolution until it fits the budget. */
    private Jpg fit(Bitmap src, long budget) {
        int sw = src.getWidth(), sh = src.getHeight();
        int big = Math.max(sw, sh);
        int target = Math.min(big, 2480);
        Jpg smallest = null;

        while (true) {
            int tw = Math.max(1, Math.round(sw * (float) target / big));
            int th = Math.max(1, Math.round(sh * (float) target / big));
            Bitmap b = flat(src, tw, th);

            Jpg hi = enc(b, 90);
            if (budget <= 0 || hi.data.length <= budget) {
                b.recycle();
                return hi;
            }
            Jpg low = enc(b, 30);
            if (smallest == null || low.data.length < smallest.data.length) smallest = low;
            if (low.data.length <= budget) {
                int lo = 30, up = 90;
                Jpg ok = low;
                while (up - lo > 4) {
                    int mid = (lo + up) / 2;
                    Jpg t = enc(b, mid);
                    if (t.data.length <= budget) {
                        ok = t;
                        lo = mid;
                    } else {
                        up = mid;
                    }
                }
                b.recycle();
                return ok;
            }
            b.recycle();
            if (target <= 160) return smallest;
            target = Math.max(160, Math.round(target * 0.8f));
        }
    }

    private Bitmap flat(Bitmap src, int tw, int th) {
        Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                new Rect(0, 0, tw, th), new Paint(Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    private Jpg enc(Bitmap b, int q) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, bos);
        Jpg j = new Jpg();
        j.data = bos.toByteArray();
        j.w = b.getWidth();
        j.h = b.getHeight();
        return j;
    }

    private Bitmap loadOriented(Uri uri, int minSide) throws IOException {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int big = Math.max(o.outWidth, o.outHeight);
        if (big <= 0) throw new IOException("Cannot read image");
        int sample = 1;
        while (big / (sample * 2) >= minSide) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bmp == null) throw new IOException("Cannot decode image");

        int deg = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) {
        }
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        return bmp;
    }

    // ---------------------------------------------------------------- open / share

    private void openPdf() {
        if (lastPdf == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastPdf, "application/pdf");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No PDF viewer installed");
        }
    }

    private void sharePdf() {
        if (lastPdf == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, lastPdf);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share PDF"));
    }
}
