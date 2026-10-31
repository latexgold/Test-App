package com.latexgold.testapp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        buildUi();
        findFlashCamera();
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

    private void findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics info = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = info.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(hasFlash)
                        && (lensFacing == null || lensFacing == CameraCharacteristics.LENS_FACING_BACK)) {
                    cameraId = id;
                    return;
                }
            }
            toggleButton.setEnabled(false);
            toggleButton.setText("No flashlight available");
        } catch (CameraAccessException e) {
            toggleButton.setEnabled(false);
            toggleButton.setText("Flashlight unavailable");
        }
    }

    private void toggleFlashlight() {
        if (cameraId == null) {
            Toast.makeText(this, "No flashlight available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        setFlashlight(!isOn);
    }

    private void setFlashlight(boolean turnOn) {
        try {
            cameraManager.setTorchMode(cameraId, turnOn);
            isOn = turnOn;
            toggleButton.setText(isOn ? "Turn Off" : "Turn On");
        } catch (CameraAccessException | IllegalArgumentException e) {
            Toast.makeText(this, "Could not change flashlight state", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFlashlight(true);
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            Toast.makeText(this, "Camera permission is required for the flashlight", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        if (isOn && cameraManager != null && cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, false);
                isOn = false;
            } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
            }
        }
        super.onPause();
    }
}
