package com.latexgold.testapp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private CameraManager cameraManager;
    private String cameraId;
    private Button toggleButton;
    private boolean isOn;
    private boolean cameraAvailable;
    private boolean resumed;
    private boolean callbackRegistered;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String changedCameraId, boolean enabled) {
            if (changedCameraId.equals(cameraId)) {
                isOn = enabled;
                cameraAvailable = true;
                updateButton();
            }
        }

        @Override
        public void onTorchModeUnavailable(String changedCameraId) {
            if (changedCameraId.equals(cameraId)) {
                cameraAvailable = false;
                updateButton();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        buildUi();
        refreshFlashCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (hasCameraPermission()) {
            registerTorchCallback();
        } else {
            cameraId = null;
            cameraAvailable = false;
            isOn = false;
        }
        refreshFlashCamera();
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Turn off while this screen leaves the foreground, including app switching.
        if (isOn && cameraManager != null && cameraId != null && hasCameraPermission()) {
            try {
                cameraManager.setTorchMode(cameraId, false);
            } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
                // Torch state is refreshed from the system callback on the next resume.
            }
        }
        // Never retain a stale local ON state across a pause; onResume refreshes it.
        isOn = false;
        unregisterTorchCallback();
        super.onPause();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerTorchCallback() {
        if (cameraManager != null && !callbackRegistered && hasCameraPermission()) {
            try {
                cameraManager.registerTorchCallback(torchCallback, mainHandler);
                callbackRegistered = true;
            } catch (SecurityException ignored) {
                cameraAvailable = false;
            }
        }
    }

    private void unregisterTorchCallback() {
        if (cameraManager != null && callbackRegistered) {
            cameraManager.unregisterTorchCallback(torchCallback);
            callbackRegistered = false;
        }
    }

    private void buildUi() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        toggleButton = new Button(this);
        toggleButton.setText("Turn On");
        toggleButton.setTextSize(24);
        toggleButton.setMinHeight(72);
        toggleButton.setOnClickListener(v -> toggleFlashlight());
        layout.addView(toggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
    }

    private void refreshFlashCamera() {
        if (cameraManager == null) {
            cameraId = null;
            cameraAvailable = false;
            updateButton();
            return;
        }
        // Camera metadata access may throw before the user grants runtime permission.
        if (!hasCameraPermission()) {
            cameraId = null;
            cameraAvailable = false;
            isOn = false;
            updateButton();
            return;
        }

        String previousCameraId = cameraId;
        try {
            String selectedId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics info = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = info.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash)
                        && (lensFacing == null || lensFacing == CameraCharacteristics.LENS_FACING_BACK)) {
                    selectedId = id;
                    break;
                }
            }
            cameraId = selectedId;
            if (cameraId == null) {
                isOn = false;
                cameraAvailable = false;
            } else if (!cameraId.equals(previousCameraId)) {
                isOn = false;
                cameraAvailable = true;
            }
            updateButton();
        } catch (CameraAccessException | SecurityException e) {
            cameraAvailable = false;
            updateButton();
        }
    }

    private void toggleFlashlight() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (cameraId == null) {
            refreshFlashCamera();
            if (cameraId == null) {
                Toast.makeText(this, "No flashlight available", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!cameraAvailable) {
            Toast.makeText(this, "Flashlight is temporarily unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        setFlashlight(!isOn);
    }

    private void setFlashlight(boolean turnOn) {
        if (!resumed || cameraManager == null || cameraId == null || !hasCameraPermission()) {
            return;
        }
        try {
            cameraManager.setTorchMode(cameraId, turnOn);
            isOn = turnOn;
            updateButton();
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            // Avoid displaying stale ON after an unsuccessful request to turn it off.
            if (!turnOn) {
                isOn = false;
            }
            updateButton();
            Toast.makeText(this, "Could not change flashlight state", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateButton() {
        if (toggleButton == null) {
            return;
        }
        if (!hasCameraPermission()) {
            // Keep the single button available so its tap can request permission.
            toggleButton.setEnabled(true);
            toggleButton.setText("Turn On");
        } else if (cameraId == null) {
            toggleButton.setEnabled(false);
            toggleButton.setText("No flashlight available");
        } else if (!cameraAvailable) {
            toggleButton.setEnabled(false);
            toggleButton.setText("Flashlight unavailable");
        } else {
            toggleButton.setEnabled(true);
            toggleButton.setText(isOn ? "Turn Off" : "Turn On");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) {
                registerTorchCallback();
                refreshFlashCamera();
                setFlashlight(true);
            }
        } else {
            updateButton();
            Toast.makeText(this, "Camera permission is required for the flashlight", Toast.LENGTH_LONG).show();
        }
    }
}
