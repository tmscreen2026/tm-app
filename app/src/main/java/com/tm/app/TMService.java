package com.tm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TMService extends Service {

    private static final String CHANNEL_ID = "tm_channel";
    private static final int NOTIF_ID = 1;
    private static final String SERVER = "http://127.0.0.1:8765";
    private static final long INTERVAL_MS = 3000;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int screenWidth;
    private int screenHeight;
    private int screenDpi;

    private volatile boolean busy = false;
    private volatile boolean stopped = false;
    private long lastSent = 0;
    private String lastText = "";
    private int savedCount = 0;
    private int lastHash = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && "STOP".equals(intent.getAction())) {
            stopped = true;
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("TM running"));

        if (intent != null && intent.hasExtra("resultCode")) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");
            startCapture(resultCode, data);
        }

        return START_STICKY;
    }

    private void startCapture(int resultCode, Intent data) {
        if (data == null) return;
        if (mediaProjection != null) return;

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDpi = metrics.densityDpi;

        MediaProjectionManager mpm = (MediaProjectionManager)
            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "TM",
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null, null);

        imageReader.setOnImageAvailableListener(
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    if (stopped || busy) {
                        Image img = reader.acquireLatestImage();
                        if (img != null) img.close();
                        return;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastSent < INTERVAL_MS) {
                        Image img = reader.acquireLatestImage();
                        if (img != null) img.close();
                        return;
                    }

                    Image image = reader.acquireLatestImage();
                    if (image == null) return;

                    Bitmap bmp = imageToBitmap(image);
                    image.close();

                    if (bmp == null) return;

                    int hash = quickHash(bmp);
                    if (hash == lastHash) {
                        bmp.recycle();
                        lastSent = now;
                        return;
                    }
                    lastHash = hash;

                    busy = true;
                    lastSent = now;
                    sendToServer(bmp);
                }
            }, null);
    }

    private int quickHash(Bitmap bmp) {
        int h = 0;
        int stepX = bmp.getWidth() / 16;
        int stepY = bmp.getHeight() / 16;
        if (stepX < 1) stepX = 1;
        if (stepY < 1) stepY = 1;
        for (int y = 0; y < bmp.getHeight(); y += stepY) {
            for (int x = 0; x < bmp.getWidth(); x += stepX) {
                h = h * 31 + bmp.getPixel(x, y);
            }
        }
        return h;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bmp = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);

        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);
        if (cropped != bmp) bmp.recycle();
        return cropped;
    }

    private void sendToServer(Bitmap bmp) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(
                bmp, screenWidth / 2, screenHeight / 2, true);
            if (scaled != bmp) bmp.recycle();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 50, baos);
            scaled.recycle();

            final byte[] imgBytes = baos.toByteArray();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(SERVER);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(120000);
                        conn.setFixedLengthStreamingMode(imgBytes.length);

                        OutputStream os = conn.getOutputStream();
                        os.write(imgBytes);
                        os.flush();
                        os.close();

                        int code = conn.getResponseCode();
                        if (code != 200) return;

                        InputStream is = conn.getInputStream();
                        ByteArrayOutputStream resp = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) > 0) resp.write(buf, 0, n);
                        is.close();

                        String text = new String(resp.toByteArray(), "UTF-8").trim();
                        if (text.isEmpty()) return;
                        if (text.equals(lastText)) return;

                        lastText = text;
                        saveText(text);
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        if (conn != null) conn.disconnect();
                        busy = false;
                    }
                }
            }).start();
        } catch (Exception e) {
            busy = false;
        }
    }

    private void saveText(String text) {
        try {
            File dir = new File(getFilesDir(), "notes");
            if (!dir.exists()) dir.mkdirs();

            Date now = new Date();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(now);
            String header = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(now) + "]\n";
            File out = new File(dir, ts + ".txt");

            FileOutputStream fos = new FileOutputStream(out);
            fos.write((header + text).getBytes("UTF-8"));
            fos.close();

            savedCount++;
            updateNotification("Saved " + savedCount + " - tap Stop to end");
        } catch (Exception e) {
            // ignore
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "TM Service",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("TM screen capture service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, TMService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("TM")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                           "Stop", stopPi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager)
            getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopped = true;
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
