package com.quickscan.app;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_CAM = 11;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Uri pendingUri;
    private long jpgLimit = 0, pdfLimit = 0;
    private int lastType = 1; // 1 = JPEG, 2 = PDF

    private EditText folderEt, jpgEt, pdfEt;
    private Spinner jpgSp, pdfSp;
    private CheckBox contBox;
    private TextView logTv;

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
        folderEt.setHint("e.g. MyFolder");
        folderEt.setSingleLine(true);
        root.addView(folderEt, mw());
        root.addView(small("Files are saved in Download/<folder name>"));

        root.addView(label("2. Size of ALL JPEG files (empty = no limit)"));
        jpgEt = new EditText(this);
        jpgSp = newUnit();
        root.addView(sizeRow(jpgEt, jpgSp), mw());

        root.addView(label("3. Size of ALL PDF files (empty = no limit)"));
        pdfEt = new EditText(this);
        pdfSp = newUnit();
        root.addView(sizeRow(pdfEt, pdfSp), mw());

        root.addView(label("4. Take photos"));
        root.addView(btn("Take photo", v -> takePhoto()), mw());
        contBox = new CheckBox(this);
        contBox.setText("Keep camera open after saving (quick mode)");
        root.addView(contBox, mw());
        root.addView(small("After each photo you type its name and choose JPEG or PDF."));

        root.addView(label("Saved files"));
        logTv = new TextView(this);
        logTv.setText("(nothing saved yet)");
        root.addView(logTv, mw());

        setContentView(sv);
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

    private Spinner newUnit() {
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"KB", "MB"}));
        return sp;
    }

    private LinearLayout sizeRow(EditText et, Spinner sp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        et.setHint("e.g. 200");
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sp, new LinearLayout.LayoutParams(-2, -2));
        return row;
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

    private static long readSize(EditText et, Spinner sp) {
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return 0;
        double v = Double.parseDouble(s);
        return (long) (v * (sp.getSelectedItemPosition() == 0 ? 1024L : 1048576L));
    }

    // ---------------------------------------------------------------- camera

    private void takePhoto() {
        if (clean(folderEt.getText().toString()) == null) {
            toast("Enter a folder name first");
            return;
        }
        try {
            jpgLimit = readSize(jpgEt, jpgSp);
            pdfLimit = readSize(pdfEt, pdfSp);
        } catch (NumberFormatException e) {
            toast("Invalid size number");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "tmp_" + ts + ".jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuickScan_tmp");
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
            deleteQuiet(pendingUri);
            pendingUri = null;
            toast("No camera app found");
        }
    }

    private void deleteQuiet(Uri u) {
        if (u == null) return;
        try {
            getContentResolver().delete(u, null, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAM) {
            Uri tmp = pendingUri;
            pendingUri = null;
            if (res == RESULT_OK && tmp != null) askName(tmp);
            else deleteQuiet(tmp);
        }
    }

    private void askName(final Uri tmp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("File name (e.g. a, 23, qw)");
        nameEt.setSingleLine(true);
        box.addView(nameEt, mw());

        final RadioGroup rg = new RadioGroup(this);
        RadioButton rj = new RadioButton(this);
        rj.setText("JPEG  (.jpeg)");
        rj.setId(1);
        RadioButton rp = new RadioButton(this);
        rp.setText("PDF  (.pdf)");
        rp.setId(2);
        rg.addView(rj);
        rg.addView(rp);
        rg.check(lastType);
        box.addView(rg, mw());

        new AlertDialog.Builder(this)
                .setTitle("Save photo as")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Save", (dlg, which) -> {
                    lastType = rg.getCheckedRadioButtonId() == 2 ? 2 : 1;
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    String nm = clean(nameEt.getText().toString());
                    if (nm != null) {
                        String low = nm.toLowerCase(Locale.ROOT);
                        for (String ext : new String[]{".jpeg", ".jpg", ".pdf"}) {
                            if (low.endsWith(ext)) {
                                nm = nm.substring(0, nm.length() - ext.length());
                                break;
                            }
                        }
                    }
                    if (nm == null || nm.isEmpty()) nm = "IMG_" + ts;
                    String fo = clean(folderEt.getText().toString());
                    if (fo == null) fo = "QuickScan";
                    saveJob(tmp, fo, nm, lastType == 2, jpgLimit, pdfLimit);
                    if (contBox.isChecked()) takePhoto();
                })
                .setNegativeButton("Discard", (dlg, which) -> deleteQuiet(tmp))
                .show();
    }

    private void saveJob(final Uri tmp, final String folder, final String name,
                         final boolean pdf, final long jl, final long pl) {
        final String fileName = name + (pdf ? ".pdf" : ".jpeg");
        toast("Saving " + fileName + " ...");
        exec.execute(() -> {
            String line;
            try {
                long limit = pdf ? pl : jl;
                long sz = pdf ? writePdf(tmp, folder, fileName, pl) : writeJpeg(tmp, folder, fileName, jl);
                line = "OK  " + folder + "/" + fileName + "  -  " + fmt(sz);
                if (limit > 0 && sz > limit) line += "  (could not reach " + fmt(limit) + ")";
            } catch (Throwable t) {
                line = "FAILED  " + fileName + "  -  " + t;
            }
            deleteQuiet(tmp);
            final String l = line;
            runOnUiThread(() -> {
                String old = logTv.getText().toString();
                logTv.setText(old.startsWith("(") ? l : l + "\n" + old);
            });
        });
    }

    // ---------------------------------------------------------------- JPEG

    private long writeJpeg(Uri tmp, String folder, String fileName, long limit) throws Exception {
        Bitmap src = loadOriented(tmp, 4000);
        Jpg j = fit(src, limit, 4000);
        src.recycle();
        byte[] data = j.data;
        if (limit > 0 && data.length < limit) data = padJpeg(data, (int) (limit - data.length));
        saveBytes(fileName, "image/jpeg", folder, data);
        return data.length;
    }

    /** Adds harmless JPEG comment blocks so the file is exactly the wanted size. */
    private static byte[] padJpeg(byte[] d, int pad) {
        int pos = 2;
        while (pos + 4 <= d.length && (d[pos] & 0xFF) == 0xFF
                && (d[pos + 1] & 0xFF) >= 0xE0 && (d[pos + 1] & 0xFF) <= 0xEF) {
            pos += 2 + (((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF));
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(d.length + pad);
        bo.write(d, 0, pos);
        int rem = pad;
        while (rem >= 4) {
            int t = Math.min(rem, 65537);
            int left = rem - t;
            if (left > 0 && left < 4) t = rem - 4;
            bo.write(0xFF);
            bo.write(0xFE);
            bo.write((t - 2) >> 8);
            bo.write((t - 2) & 0xFF);
            bo.write(new byte[t - 4], 0, t - 4);
            rem -= t;
        }
        bo.write(d, pos, d.length - pos);
        for (int i = 0; i < rem; i++) bo.write(0);
        return bo.toByteArray();
    }

    // ---------------------------------------------------------------- PDF (one photo = one page)

    private long writePdf(Uri tmp, String folder, String fileName, long limit) throws Exception {
        Bitmap src = loadOriented(tmp, 2480);
        boolean land = src.getWidth() > src.getHeight();
        float pw = land ? 842f : 595f;
        float ph = land ? 595f : 842f;
        float m = 14f;
        float sc = Math.min((pw - 2 * m) / src.getWidth(), (ph - 2 * m) / src.getHeight());
        float dw = src.getWidth() * sc;
        float dh = src.getHeight() * sc;
        float x = (pw - dw) / 2f;
        float y = (ph - dh) / 2f;

        long budget = limit > 0 ? Math.max(2000L, limit - 1200L) : 0;
        Jpg j;
        byte[] pdf;
        while (true) {
            j = fit(src, budget, 2480);
            pdf = pdfBytes(j, pw, ph, dw, dh, x, y, 0);
            if (limit <= 0 || pdf.length <= limit || budget <= 2000L) break;
            budget -= (pdf.length - limit) + 50;
            if (budget < 2000L) budget = 2000L;
        }
        src.recycle();

        if (limit > 0 && pdf.length < limit) {
            int pad = (int) (limit - pdf.length);
            for (int i = 0; i < 6; i++) {
                pdf = pdfBytes(j, pw, ph, dw, dh, x, y, pad);
                long diff = limit - pdf.length;
                if (diff == 0) break;
                pad = Math.max(0, pad + (int) diff);
            }
        }
        saveBytes(fileName, "application/pdf", folder, pdf);
        return pdf.length;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] pdfBytes(Jpg j, float pw, float ph, float dw, float dh,
                                   float x, float y, int pad) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(j.data.length + 2048 + pad);
        int[] offs = new int[6];

        o.write(ascii("%PDF-1.4\n"));
        offs[1] = o.size();
        o.write(ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"));
        offs[2] = o.size();
        o.write(ascii("2 0 obj\n<< /Type /Pages /Count 1 /Kids [ 3 0 R ] >>\nendobj\n"));
        offs[3] = o.size();
        o.write(ascii(String.format(Locale.US,
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] "
                        + "/Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>\nendobj\n", pw, ph)));

        byte[] cb = ascii(String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", dw, dh, x, y));
        offs[4] = o.size();
        o.write(ascii("4 0 obj\n<< /Length " + cb.length + " >>\nstream\n"));
        o.write(cb);
        o.write(ascii("\nendstream\nendobj\n"));

        offs[5] = o.size();
        o.write(ascii("5 0 obj\n<< /Type /XObject /Subtype /Image /Width " + j.w + " /Height " + j.h
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                + j.data.length + " >>\nstream\n"));
        o.write(j.data);
        o.write(ascii("\nendstream\nendobj\n"));

        if (pad >= 2) {
            byte[] filler = new byte[pad - 2];
            Arrays.fill(filler, (byte) 'x');
            o.write('%');
            o.write(filler, 0, filler.length);
            o.write('\n');
        } else if (pad == 1) {
            o.write('\n');
        }

        int xref = o.size();
        o.write(ascii("xref\n0 6\n0000000000 65535 f \n"));
        for (int i = 1; i <= 5; i++) {
            o.write(ascii(String.format(Locale.US, "%010d 00000 n \n", offs[i])));
        }
        o.write(ascii("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- saving

    private void saveBytes(String fileName, String mime, String folder, byte[] data) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + folder);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) throw new IOException("Cannot create " + fileName);
        try {
            try (OutputStream os = cr.openOutputStream(u)) {
                os.write(data);
            }
            ContentValues d = new ContentValues();
            d.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, d, null, null);
        } catch (IOException | RuntimeException e) {
            deleteQuiet(u);
            throw e;
        }
    }

    // ---------------------------------------------------------------- image helpers

    private static class Jpg {
        byte[] data;
        int w, h;
    }

    /** Compress to JPEG; lowers quality, then resolution, until it fits the budget (0 = no limit). */
    private Jpg fit(Bitmap src, long budget, int maxLong) {
        int sw = src.getWidth(), sh = src.getHeight();
        int big = Math.max(sw, sh);
        int target = Math.min(big, maxLong);
        Jpg smallest = null;

        while (true) {
            int tw = Math.max(1, Math.round(sw * (float) target / big));
            int th = Math.max(1, Math.round(sh * (float) target / big));
            Bitmap b = flat(src, tw, th);

            Jpg hi = enc(b, 92);
            if (budget <= 0 || hi.data.length <= budget) {
                b.recycle();
                return hi;
            }
            Jpg low = enc(b, 30);
            if (smallest == null || low.data.length < smallest.data.length) smallest = low;
            if (low.data.length <= budget) {
                int lo = 30, up = 92;
                Jpg ok = low;
                while (up - lo > 2) {
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
}
