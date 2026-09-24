package com.tm.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;

    private Button startBtn;
    private Button viewBtn;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        startBtn = new Button(this);
        startBtn.setText("TM - Start Service");

        viewBtn = new Button(this);
        viewBtn.setText("View Notes");

        status = new TextView(this);
        status.setText("status: ready");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);

        layout.addView(startBtn);
        layout.addView(viewBtn);
        layout.addView(scroll);
        setContentView(layout);

        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                status.setText("requesting permission...");
                MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE);
                Intent intent = manager.createScreenCaptureIntent();
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
            }
        });

        viewBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNotes();
            }
        });
    }

    private void showNotes() {
        File dir = new File(getFilesDir(), "notes");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            status.setText("No notes saved yet.");
            return;
        }

        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("Total: ").append(files.length).append(" notes\n\n");

        int show = Math.min(files.length, 10);
        for (int i = 0; i < show; i++) {
            File f = files[i];
            sb.append("===== ").append(f.getName()).append(" =====\n");
            try {
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                fis.read(buf);
                fis.close();
                sb.append(new String(buf, "UTF-8")).append("\n\n");
            } catch (Exception e) {
                sb.append("(read error)\n\n");
            }
        }

        status.setText(sb.toString());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SCREEN_CAPTURE_REQUEST) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("permission denied");
            return;
        }

        Intent svc = new Intent(this, TMService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        status.setText("service started. You can leave this app.");
    }
}
