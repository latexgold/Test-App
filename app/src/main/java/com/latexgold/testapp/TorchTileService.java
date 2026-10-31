package com.latexgold.testapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class TorchTileService extends TileService {
    private static final String PREFS = "flashlight_settings";
    @Override public void onClick() {
        super.onClick();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent); return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = null;
            for (String candidate : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
                if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) { id = candidate; break; }
            }
            if (id == null) { Toast.makeText(this,"No flashlight found",Toast.LENGTH_SHORT).show(); return; }
            boolean newState = !getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("torch_on",false);
            manager.setTorchMode(id,newState);
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("torch_on",newState).apply();
            updateTile(newState);
            sendBroadcast(new Intent(this,TorchWidgetProvider.class).setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE));
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Toast.makeText(this,"Flashlight is unavailable",Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void onStartListening() { super.onStartListening(); updateTile(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("torch_on",false)); }
    private void updateTile(boolean on) { Tile tile=getQsTile(); if(tile!=null){tile.setState(on?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);tile.setLabel(on?"Torch on":"Torch");if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(on?"Tap to turn off":"Tap to turn on");tile.updateTile();} }
}
