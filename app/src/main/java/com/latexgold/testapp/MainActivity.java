package com.latexgold.testapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final String PREFS = "flashlight_settings";
    private static final int ACCENT = Color.rgb(255, 190, 75);

    private CameraManager cameraManager;
    private SharedPreferences prefs;
    private String cameraId;
    private boolean isOn;
    private boolean cameraAvailable;
    private boolean callbackRegistered;
    private Button powerButton;
    private TextView statusView;
    private TextView timerView;
    private TextView intensityValue;
    private SeekBar intensitySeek;
    private int maxStrength = 1;
    private int strength = 1;
    private long offAtElapsed;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!isOn || offAtElapsed == 0) return;
            long remaining = offAtElapsed - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                setTorch(false);
                Toast.makeText(MainActivity.this, "Auto-off timer finished", Toast.LENGTH_SHORT).show();
                return;
            }
            long seconds = (remaining + 999) / 1000;
            timerView.setText("Auto-off in " + (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String changedId, boolean enabled) {
            if (changedId.equals(cameraId)) {
                boolean changed = isOn != enabled;
                isOn = enabled;
                cameraAvailable = true;
                if (changed && enabled) startAutoOff();
                if (!enabled) cancelTimer();
                renderState();
            }
        }
        @Override public void onTorchModeUnavailable(String changedId) {
            if (changedId.equals(cameraId)) {
                cameraAvailable = false;
                renderState();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        strength = prefs.getInt("strength", 1);
        buildUi();
        refreshCamera();
        applyKeepScreenOn();
        renderState();
    }

    @Override protected void onStart() {
        super.onStart();
        registerTorchCallback();
        refreshCamera();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCamera();
        applyKeepScreenOn();
    }

    @Override protected void onStop() {
        if (prefs.getBoolean("off_when_leaving", true) && isOn) setTorch(false);
        if (callbackRegistered && cameraManager != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
            callbackRegistered = false;
        }
        super.onStop();
    }

    private void buildUi() {
        boolean dark = prefs.getBoolean("dark_theme", true);
        final int bg = dark ? Color.rgb(10, 18, 32) : Color.rgb(242, 246, 250);
        final int card = dark ? Color.rgb(20, 31, 49) : Color.WHITE;
        final int fg = dark ? Color.rgb(240, 245, 251) : Color.rgb(23, 35, 52);
        final int muted = dark ? Color.rgb(157, 174, 195) : Color.rgb(94, 111, 131);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = new TextView(this);
        icon.setText("⚡"); icon.setTextSize(24); icon.setGravity(Gravity.CENTER);
        icon.setTextColor(Color.rgb(18, 29, 44));
        icon.setBackground(round(ACCENT, dp(16)));
        header.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(14), 0, 0, 0);
        TextView title = label("Torch", 27, fg, true);
        TextView subtitle = label("A little light, right when you need it.", 14, muted, false);
        titleBox.addView(title); titleBox.addView(subtitle);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView version = label("1.1", 12, muted, true);
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        version.setBackground(round(card, dp(20)));
        header.addView(version);
        page.addView(header);

        LinearLayout hero = card(card);
        hero.setGravity(Gravity.CENTER);
        hero.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, dp(330));
        heroLp.topMargin = dp(26);
        page.addView(hero, heroLp);

        statusView = label("READY", 13, muted, true);
        statusView.setLetterSpacing(.12f);
        hero.addView(statusView);
        powerButton = new Button(this);
        powerButton.setText("⏻");
        powerButton.setTextSize(54);
        powerButton.setTextColor(Color.rgb(31, 38, 48));
        powerButton.setAllCaps(false);
        powerButton.setElevation(dp(8));
        powerButton.setBackground(round(ACCENT, 1000));
        powerButton.setContentDescription("Turn flashlight on");
        powerButton.setOnClickListener(v -> {
            if (prefs.getBoolean("haptics", true)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            toggleTorch();
        });
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(dp(152), dp(152));
        powerLp.topMargin = dp(22); powerLp.bottomMargin = dp(18);
        hero.addView(powerButton, powerLp);
        timerView = label("Tap to turn on", 15, muted, false);
        hero.addView(timerView);

        TextView settingsTitle = label("Quick settings", 19, fg, true);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.topMargin = dp(26); stLp.bottomMargin = dp(12);
        page.addView(settingsTitle, stLp);

        LinearLayout settings = card(card);
        settings.setOrientation(LinearLayout.VERTICAL);
        page.addView(settings);

        TextView autoOff = label("Auto-off · " + autoOffLabel(prefs.getInt("auto_off", 10)), 16, fg, true);
        autoOff.setGravity(Gravity.CENTER_VERTICAL);
        autoOff.setPadding(dp(16), dp(17), dp(16), dp(17));
        autoOff.setContentDescription("Auto-off timer. Tap to change.");
        autoOff.setOnClickListener(v -> chooseAutoOff());
        settings.addView(autoOff, new LinearLayout.LayoutParams(-1, dp(58)));
        divider(settings, dark);

        LinearLayout intensityRow = new LinearLayout(this);
        intensityRow.setOrientation(LinearLayout.VERTICAL);
        intensityRow.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout intensityHead = new LinearLayout(this);
        intensityHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView intensityLabel = label("Brightness", 16, fg, true);
        intensityHead.addView(intensityLabel, new LinearLayout.LayoutParams(0, -2, 1));
        intensityValue = label("", 14, muted, true);
        intensityHead.addView(intensityValue);
        intensityRow.addView(intensityHead);
        intensitySeek = new SeekBar(this);
        intensitySeek.setMax(Math.max(1, maxStrength - 1));
        intensitySeek.setProgress(Math.max(0, strength - 1));
        intensitySeek.setContentDescription("Flashlight brightness");
        intensitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                strength = progress + 1;
                prefs.edit().putInt("strength", strength).apply();
                updateIntensityLabel();
                if (fromUser && isOn && Build.VERSION.SDK_INT >= 33 && maxStrength > 1) {
                    try { cameraManager.turnOnTorchWithStrengthLevel(cameraId, strength); }
                    catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
                        Toast.makeText(MainActivity.this, "Brightness could not be changed", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        intensityRow.addView(intensitySeek);
        settings.addView(intensityRow);
        divider(settings, dark);
        addSwitch(settings, "Turn off when leaving", "Safer when you switch apps", "off_when_leaving", true, fg, muted);
        divider(settings, dark);
        addSwitch(settings, "Keep screen awake", "Prevent the display from sleeping", "keep_awake", false, fg, muted);
        divider(settings, dark);
        addSwitch(settings, "Haptic feedback", "A small vibration on tap", "haptics", true, fg, muted);

        Button appearance = new Button(this);
        appearance.setText("Appearance: " + (dark ? "Dark" : "Light"));
        appearance.setTextSize(15);
        appearance.setAllCaps(false);
        appearance.setTextColor(fg);
        appearance.setBackground(round(card, dp(16)));
        appearance.setOnClickListener(v -> chooseAppearance());
        LinearLayout.LayoutParams appearanceLp = new LinearLayout.LayoutParams(-1, dp(54));
        appearanceLp.topMargin = dp(14);
        page.addView(appearance, appearanceLp);

        TextView footer = label("Flash control uses your phone’s camera permission. Brightness levels depend on your device.", 13, muted, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(12), dp(18), dp(12), dp(8));
        page.addView(footer);
        setContentView(scroll);
        updateIntensityLabel();
    }

    private void addSwitch(LinearLayout parent, String title, String detail, String key, boolean defaultValue, int fg, int muted) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(label(title, 16, fg, true));
        words.addView(label(detail, 13, muted, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(prefs.getBoolean(key, defaultValue));
        toggle.setContentDescription(title);
        row.addView(toggle);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if ("keep_awake".equals(key)) applyKeepScreenOn();
            if ("off_when_leaving".equals(key) && checked && !isOn) cancelTimer();
            if ("haptics".equals(key) && checked) button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(70)));
    }

    private void chooseAutoOff() {
        final int[] values = {0, 1, 5, 10, 30};
        String[] labels = {"Never", "1 minute", "5 minutes", "10 minutes", "30 minutes"};
        int current = prefs.getInt("auto_off", 10);
        int selected = 3;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        new AlertDialog.Builder(this).setTitle("Auto-off timer")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    prefs.edit().putInt("auto_off", values[which]).apply();
                    if (isOn) startAutoOff();
                    buildUi(); refreshCamera(); renderState(); dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseAppearance() {
        String[] modes = {"Dark", "Light"};
        boolean dark = prefs.getBoolean("dark_theme", true);
        new AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(modes, dark ? 0 : 1, (dialog, which) -> {
                    prefs.edit().putBoolean("dark_theme", which == 0).apply();
                    recreate();
                }).setNegativeButton("Cancel", null).show();
    }

    private String autoOffLabel(int minutes) { return minutes == 0 ? "Never" : minutes + (minutes == 1 ? " minute" : " minutes"); }

    private void toggleTorch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (cameraId == null) refreshCamera();
        if (cameraId == null) {
            Toast.makeText(this, "No flashlight is available on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cameraAvailable) {
            Toast.makeText(this, "Flashlight is temporarily unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        setTorch(!isOn);
    }

    private void setTorch(boolean turnOn) {
        if (cameraManager == null || cameraId == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (turnOn && Build.VERSION.SDK_INT >= 33 && maxStrength > 1) {
                int level = Math.max(1, Math.min(strength, maxStrength));
                cameraManager.turnOnTorchWithStrengthLevel(cameraId, level);
            } else {
                cameraManager.setTorchMode(cameraId, turnOn);
            }
            isOn = turnOn;
            if (turnOn) startAutoOff(); else cancelTimer();
            renderState();
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            if (!turnOn) { isOn = false; cancelTimer(); }
            renderState();
            Toast.makeText(this, "Could not change flashlight state", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshCamera() {
        if (cameraManager == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraId = null; cameraAvailable = false; maxStrength = 1; updateIntensityLabel(); renderState(); return;
        }
        try {
            String found = null;
            int foundMax = 1;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics info = cameraManager.getCameraCharacteristics(id);
                Boolean flash = info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    found = id;
                    if (Build.VERSION.SDK_INT >= 33) {
                        Integer max = info.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                        if (max != null) foundMax = Math.max(1, max);
                    }
                    break;
                }
            }
            if (found == null) {
                cameraId = null; cameraAvailable = false; isOn = false; maxStrength = 1;
            } else {
                if (!found.equals(cameraId)) { cameraId = found; isOn = false; }
                maxStrength = foundMax; cameraAvailable = true;
            }
            strength = Math.min(Math.max(1, strength), maxStrength);
            if (intensitySeek != null) {
                intensitySeek.setMax(Math.max(1, maxStrength - 1));
                intensitySeek.setProgress(Math.max(0, strength - 1));
                intensitySeek.setEnabled(maxStrength > 1);
            }
            updateIntensityLabel(); renderState();
        } catch (CameraAccessException | SecurityException e) {
            cameraAvailable = false; renderState();
        }
    }

    private void registerTorchCallback() {
        if (cameraManager != null && !callbackRegistered && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { cameraManager.registerTorchCallback(torchCallback, handler); callbackRegistered = true; }
            catch (SecurityException ignored) { cameraAvailable = false; }
        }
    }

    private void startAutoOff() {
        cancelTimer();
        int minutes = prefs.getInt("auto_off", 10);
        if (minutes > 0 && isOn) {
            offAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L;
            handler.post(timerTick);
        }
    }

    private void cancelTimer() {
        handler.removeCallbacks(timerTick);
        offAtElapsed = 0;
        if (timerView != null && !isOn) timerView.setText("Tap to turn on");
    }

    private void renderState() {
        if (powerButton == null || statusView == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            powerButton.setEnabled(true); powerButton.setText("⏻"); powerButton.setContentDescription("Grant camera permission and turn flashlight on");
            statusView.setText("PERMISSION NEEDED"); timerView.setText("Tap to allow camera access");
        } else if (cameraId == null) {
            powerButton.setEnabled(false); statusView.setText("NO FLASHLIGHT FOUND"); timerView.setText("This device has no rear flash");
        } else if (!cameraAvailable) {
            powerButton.setEnabled(false); statusView.setText("TEMPORARILY UNAVAILABLE"); timerView.setText("Try again in a moment");
        } else {
            powerButton.setEnabled(true);
            powerButton.setText(isOn ? "Ⅱ" : "⏻");
            powerButton.setContentDescription(isOn ? "Turn flashlight off" : "Turn flashlight on");
            statusView.setText(isOn ? "TORCH IS ON" : "READY WHEN YOU ARE");
            if (!isOn) timerView.setText("Tap to turn on");
            else if (offAtElapsed == 0) timerView.setText("Tap to turn off · no timer");
        }
        powerButton.setBackground(round(isOn ? Color.rgb(255, 214, 130) : ACCENT, 1000));
    }

    private void updateIntensityLabel() {
        if (intensityValue != null) intensityValue.setText(maxStrength > 1 ? strength + " / " + maxStrength : "Standard");
    }

    private void applyKeepScreenOn() {
        if (prefs.getBoolean("keep_awake", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            registerTorchCallback(); refreshCamera(); setTorch(true);
        } else {
            renderState(); Toast.makeText(this, "Camera permission is needed to use the flashlight", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card(int color) {
        LinearLayout view = new LinearLayout(this);
        view.setBackground(round(color, dp(24)));
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(radius); return shape;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }

    private void divider(LinearLayout parent, boolean dark) {
        View line = new View(this); line.setBackgroundColor(dark ? Color.rgb(39, 53, 72) : Color.rgb(229, 235, 241));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1)); lp.leftMargin = dp(16); lp.rightMargin = dp(16); parent.addView(line, lp);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
