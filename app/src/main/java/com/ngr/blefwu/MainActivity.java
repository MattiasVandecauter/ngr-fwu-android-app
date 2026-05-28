package com.ngr.blefwu;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_MAIN_FW = 20;
    private static final int REQUEST_RADIO_FW = 21;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText targetInput;
    private TextView mainFileLabel;
    private TextView radioFileLabel;
    private TextView logView;
    private ProgressBar progressBar;
    private Button startButton;
    private Uri mainFirmware;
    private Uri radioFirmware;
    private BleFirmwareUpdater updater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNeededPermissions();
    }

    @Override
    protected void onDestroy() {
        if (updater != null) {
            updater.close();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("NGR BLE firmware updater");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title, fullWidth());

        targetInput = new EditText(this);
        targetInput.setSingleLine(true);
        targetInput.setHint("BLE naam of MAC-adres");
        targetInput.setText(BleFirmwareUpdater.DEFAULT_TARGET);
        root.addView(targetInput, fullWidth());

        Button mainButton = new Button(this);
        mainButton.setText("Kies main firmware");
        mainButton.setOnClickListener(v -> pickFile(REQUEST_MAIN_FW));
        root.addView(mainButton, fullWidth());

        mainFileLabel = smallLabel("Geen main firmware gekozen");
        root.addView(mainFileLabel, fullWidth());

        Button radioButton = new Button(this);
        radioButton.setText("Kies radio firmware");
        radioButton.setOnClickListener(v -> pickFile(REQUEST_RADIO_FW));
        root.addView(radioButton, fullWidth());

        radioFileLabel = smallLabel("Geen radio firmware gekozen");
        root.addView(radioFileLabel, fullWidth());

        startButton = new Button(this);
        startButton.setText("Start firmware update");
        startButton.setOnClickListener(v -> startUpdate());
        root.addView(startButton, fullWidth());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        root.addView(progressBar, fullWidth());

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        LinearLayout.LayoutParams logParams = fullWidth();
        logParams.weight = 1;
        logParams.topMargin = dp(12);
        root.addView(logView, logParams);
    }

    private TextView smallLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void startUpdate() {
        if (!hasPermissions()) {
            requestNeededPermissions();
            return;
        }
        if (mainFirmware == null || radioFirmware == null) {
            appendLog("Kies eerst beide firmware files.");
            return;
        }
        startButton.setEnabled(false);
        progressBar.setProgress(0);
        updater = new BleFirmwareUpdater(this, new BleFirmwareUpdater.Listener() {
            @Override
            public void onLog(String message) {
                runOnUiThread(() -> appendLog(message));
            }

            @Override
            public void onProgress(String label, int sent, int total) {
                runOnUiThread(() -> {
                    progressBar.setProgress(total == 0 ? 0 : (int) ((sent * 1000L) / total));
                    appendLog(String.format(Locale.US, "%s: %d/%d bytes", label, sent, total));
                });
            }
        });
        String target = targetInput.getText().toString();
        executor.execute(() -> {
            try {
                updater.run(target, mainFirmware, radioFirmware);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("ERROR: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        });
    }

    private void appendLog(String message) {
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        logView.append(time + " | " + message + "\n");
        int offset = logView.getLineCount() * logView.getLineHeight();
        if (offset > logView.getHeight()) {
            logView.scrollTo(0, offset - logView.getHeight());
        }
    }

    private void requestNeededPermissions() {
        List<String> permissions = missingPermissions();
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private boolean hasPermissions() {
        return missingPermissions().isEmpty();
    }

    private List<String> missingPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addMissing(permissions, Manifest.permission.BLUETOOTH_SCAN);
            addMissing(permissions, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addMissing(permissions, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions;
    }

    private void addMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(permission);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (requestCode == REQUEST_MAIN_FW) {
            mainFirmware = uri;
            mainFileLabel.setText(uri.getLastPathSegment());
        } else if (requestCode == REQUEST_RADIO_FW) {
            radioFirmware = uri;
            radioFileLabel.setText(uri.getLastPathSegment());
        }
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
