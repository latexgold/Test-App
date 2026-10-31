package com.latexgold.testapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
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
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final String PREFS = "flashlight_settings";
    private static final String ACTION_WIDGET_TOGGLE = "com.latexgold.testapp.WIDGET_TOGGLE";
    private static final int ACCENT = Color.rgb(255, 190, 75);
    private CameraManager cameraManager;
    private SharedPreferences prefs;
    private String cameraId;
    private boolean isOn, cameraAvailable, callbackRegistered, resumed, sosActive, screenLight;
    private int maxStrength = 1, strength = 1, screenColor = Color.WHITE;
    private float oldBrightness = -1f;
    private long offAtElapsed;
    private FrameLayout powerButton;
    private ImageView powerIcon;
    private TextView statusView, timerView, intensityValue;
    private SeekBar intensitySeek;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!isOn || offAtElapsed == 0 || timerView == null) return;
            long remaining = offAtElapsed - SystemClock.elapsedRealtime();
            if (remaining <= 0) { setTorch(false); Toast.makeText(MainActivity.this, "Auto-off timer finished", Toast.LENGTH_SHORT).show(); return; }
            long seconds = (remaining + 999) / 1000;
            timerView.setText("Auto-off in " + (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(cameraId)) { isOn = enabled; cameraAvailable = true; prefs.edit().putBoolean("torch_on", enabled).apply(); updateRemoteControls(); renderState(); }
        }
        @Override public void onTorchModeUnavailable(String id) { if (id.equals(cameraId)) { cameraAvailable = false; renderState(); } }
    };

    private final Runnable sosTick = new Runnable() {
        private final int[] pattern = {1,1,1,0,1,1,1,0,1,1,1,0,3,0,3,0,3,0,1,1,1,0,1,1,1,0,1,1,1,4};
        private int index;
        @Override public void run() {
            if (!sosActive) return;
            boolean on = pattern[index] > 0;
            setTorch(on);
            int units = pattern[index] == 0 ? 1 : pattern[index];
            index = (index + 1) % pattern.length;
            handler.postDelayed(this, units * 220L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        strength = prefs.getInt("strength", 1);
        buildUi(); refreshCamera(); applyKeepScreenOn();
    }
    @Override protected void onStart() { super.onStart(); registerTorchCallback(); refreshCamera(); }
    @Override protected void onResume() {
        super.onResume(); resumed = true; refreshCamera(); applyKeepScreenOn();
        if (ACTION_WIDGET_TOGGLE.equals(getIntent().getAction())) { getIntent().setAction(null); handler.post(this::toggleTorch); }
    }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onStop() {
        stopSos();
        if (prefs.getBoolean("off_when_leaving", true) && isOn) setTorch(false);
        if (callbackRegistered && cameraManager != null) { cameraManager.unregisterTorchCallback(torchCallback); callbackRegistered = false; }
        super.onStop();
    }
    @Override protected void onDestroy() { stopSos(); restoreScreenLight(); handler.removeCallbacks(timerTick); super.onDestroy(); }

    private void buildUi() {
        boolean dark = prefs.getBoolean("dark_theme", true);
        int bg = dark ? Color.rgb(10,18,32) : Color.rgb(242,246,250), surface = dark ? Color.rgb(20,31,49) : Color.WHITE;
        int fg = dark ? Color.rgb(240,245,251) : Color.rgb(23,35,52), muted = dark ? Color.rgb(157,174,195) : Color.rgb(94,111,131);
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(bg);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(22),dp(18),dp(22),dp(24)); scroll.addView(page);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("⚡",23,Color.rgb(20,31,47),true); mark.setGravity(Gravity.CENTER); mark.setBackground(round(ACCENT,dp(16))); header.addView(mark,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL); brand.setPadding(dp(13),0,0,0); brand.addView(label("Torch",25,fg,true)); brand.addView(label("Simple light. One tap.",14,muted,false)); header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version = label("1.1",12,muted,true); version.setPadding(dp(10),dp(6),dp(10),dp(6)); version.setBackground(round(surface,dp(20))); header.addView(version); page.addView(header);

        LinearLayout hero = card(surface); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(18),dp(20),dp(18),dp(18));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1,-2); heroLp.topMargin=dp(20); page.addView(hero,heroLp);
        statusView = label("READY WHEN YOU ARE",12,muted,true); statusView.setLetterSpacing(.08f); statusView.setGravity(Gravity.CENTER); statusView.setPadding(dp(14),dp(9),dp(14),dp(9)); statusView.setBackground(round(dark?Color.rgb(31,46,66):Color.rgb(232,238,245),dp(24))); hero.addView(statusView);
        powerButton = new FrameLayout(this); powerButton.setFocusable(true); powerButton.setClickable(true); powerButton.setElevation(dp(7));
        powerIcon = new ImageView(this); powerIcon.setImageResource(R.drawable.ic_flash_off); FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(62),dp(62),Gravity.CENTER); powerButton.addView(powerIcon,ip);
        powerButton.setOnClickListener(v -> { if (prefs.getBoolean("haptics",true)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); toggleTorch(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(142),dp(142)); bp.topMargin=dp(20); bp.bottomMargin=dp(14); hero.addView(powerButton,bp);
        TextView instruction = label("Tap to turn on",16,fg,true); instruction.setGravity(Gravity.CENTER); hero.addView(instruction);
        timerView = label("Your flashlight is ready",14,muted,false); timerView.setGravity(Gravity.CENTER); LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-1,-2); tlp.topMargin=dp(6); hero.addView(timerView,tlp);

        TextView modes = label("More light modes",19,fg,true); LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,-2); mlp.topMargin=dp(20); mlp.bottomMargin=dp(10); page.addView(modes,mlp);
        LinearLayout modeRow = new LinearLayout(this); modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.addView(actionButton("Screen light",surface,fg,v -> showScreenLight()),new LinearLayout.LayoutParams(0,dp(52),1));
        android.widget.Space gap = new android.widget.Space(this); modeRow.addView(gap,new LinearLayout.LayoutParams(dp(10),1));
        modeRow.addView(actionButton(sosActive?"Stop SOS":"SOS signal",surface,fg,v -> toggleSos()),new LinearLayout.LayoutParams(0,dp(52),1)); page.addView(modeRow);

        TextView settingsTitle=label("Quick settings",19,fg,true); LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2); slp.topMargin=dp(22); slp.bottomMargin=dp(10); page.addView(settingsTitle,slp);
        LinearLayout settings=card(surface); settings.setOrientation(LinearLayout.VERTICAL); page.addView(settings);
        TextView auto=label("Auto-off timer       "+autoOffLabel(prefs.getInt("auto_off",10)),15,fg,true); auto.setGravity(Gravity.CENTER_VERTICAL); auto.setPadding(dp(16),0,dp(16),0); auto.setOnClickListener(v->chooseAutoOff()); settings.addView(auto,new LinearLayout.LayoutParams(-1,dp(54))); divider(settings,dark);
        LinearLayout intensity = new LinearLayout(this); intensity.setOrientation(LinearLayout.VERTICAL); intensity.setPadding(dp(16),dp(10),dp(16),dp(8));
        LinearLayout intensityHead=new LinearLayout(this); intensityHead.setGravity(Gravity.CENTER_VERTICAL); intensityHead.addView(label("Brightness",15,fg,true),new LinearLayout.LayoutParams(0,-2,1)); intensityValue=label("",14,muted,true); intensityHead.addView(intensityValue); intensity.addView(intensityHead);
        intensitySeek=new SeekBar(this); intensitySeek.setContentDescription("Flashlight brightness"); intensitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean user){strength=p+1; prefs.edit().putInt("strength",strength).apply(); updateIntensityLabel(); if(user&&isOn&&Build.VERSION.SDK_INT>=33&&maxStrength>1) try{cameraManager.turnOnTorchWithStrengthLevel(cameraId,strength);}catch(Exception e){Toast.makeText(MainActivity.this,"Brightness unavailable",Toast.LENGTH_SHORT).show();}}
            @Override public void onStartTrackingTouch(SeekBar s){} @Override public void onStopTrackingTouch(SeekBar s){}
        }); intensity.addView(intensitySeek); settings.addView(intensity); divider(settings,dark);
        addSwitch(settings,"Turn off when leaving","Safer when switching apps","off_when_leaving",true,fg,muted); divider(settings,dark);
        addSwitch(settings,"Keep screen awake","Prevent display sleep","keep_awake",false,fg,muted); divider(settings,dark);
        addSwitch(settings,"Haptic feedback","Small vibration on tap","haptics",true,fg,muted);
        TextView appearance=label("Appearance       "+(dark?"Dark":"Light")+"       ›",15,fg,true); appearance.setGravity(Gravity.CENTER_VERTICAL); appearance.setPadding(dp(16),0,dp(16),0); appearance.setBackground(round(surface,dp(16))); appearance.setOnClickListener(v->chooseAppearance()); LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(52)); ap.topMargin=dp(12); page.addView(appearance,ap);
        TextView footer=label("No account. No internet. Just light.",13,muted,false); footer.setGravity(Gravity.CENTER); footer.setPadding(dp(10),dp(16),dp(10),dp(4)); page.addView(footer);
        setContentView(scroll); updateIntensityLabel(); renderState();
    }

    private void showScreenLight() {
        if (screenLight) return;
        screenLight=true; oldBrightness=getWindow().getAttributes().screenBrightness;
        WindowManager.LayoutParams lp=getWindow().getAttributes(); lp.screenBrightness=1f; getWindow().setAttributes(lp);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(screenColor);
        TextView close=label("×  Close screen light",15,Color.BLACK,true); close.setGravity(Gravity.CENTER); close.setBackground(round(Color.rgb(255,255,255),dp(24))); close.setPadding(dp(18),dp(12),dp(18),dp(12));
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); cp.bottomMargin=dp(34); root.addView(close,cp); close.setOnClickListener(v->restoreScreenLight());
        TextView colors=label("White     Warm     Red",14,Color.DKGRAY,true); colors.setGravity(Gravity.CENTER); colors.setBackground(round(Color.WHITE,dp(24))); colors.setPadding(dp(16),dp(12),dp(16),dp(12)); FrameLayout.LayoutParams colp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL); colp.topMargin=dp(36); root.addView(colors,colp);
        colors.setOnClickListener(v->{screenColor=screenColor==Color.WHITE?Color.rgb(255,190,130):screenColor==Color.rgb(255,190,130)?Color.rgb(255,40,40):Color.WHITE; root.setBackgroundColor(screenColor);});
        setContentView(root);
    }
    private void restoreScreenLight(){ if(!screenLight)return; screenLight=false; WindowManager.LayoutParams lp=getWindow().getAttributes(); lp.screenBrightness=oldBrightness; getWindow().setAttributes(lp); getWindow().getDecorView().setSystemUiVisibility(0); buildUi(); refreshCamera(); }
    @Override public void onBackPressed(){ if(screenLight)restoreScreenLight(); else if(sosActive)stopSos(); else super.onBackPressed(); }

    private void toggleSos(){ if(sosActive){stopSos();return;} if(!hasPermission()){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);return;} if(cameraId==null)refreshCamera(); if(cameraId==null){Toast.makeText(this,"No flashlight available",Toast.LENGTH_SHORT).show();return;} sosActive=true; renderState(); handler.removeCallbacks(sosTick); handler.post(sosTick); }
    private void stopSos(){ if(!sosActive)return; sosActive=false; handler.removeCallbacks(sosTick); if(isOn)setTorch(false); renderState(); }

    private void addSwitch(LinearLayout parent,String title,String detail,String key,boolean def,int fg,int muted){ LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16),dp(8),dp(14),dp(8)); LinearLayout words=new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.addView(label(title,15,fg,true)); words.addView(label(detail,13,muted,false)); row.addView(words,new LinearLayout.LayoutParams(0,-2,1)); Switch toggle=new Switch(this); toggle.setChecked(prefs.getBoolean(key,def)); toggle.setContentDescription(title); row.addView(toggle); toggle.setOnCheckedChangeListener((b,c)->{prefs.edit().putBoolean(key,c).apply();if("keep_awake".equals(key))applyKeepScreenOn();});parent.addView(row,new LinearLayout.LayoutParams(-1,dp(66))); }
    private void chooseAutoOff(){final int[] values={0,1,5,10,30};String[] labels={"Never","1 minute","5 minutes","10 minutes","30 minutes"};int selected=3;for(int i=0;i<values.length;i++)if(values[i]==prefs.getInt("auto_off",10))selected=i;new AlertDialog.Builder(this).setTitle("Auto-off timer").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putInt("auto_off",values[w]).apply();if(isOn)startAutoOff();buildUi();refreshCamera();d.dismiss();}).setNegativeButton("Cancel",null).show();}
    private void chooseAppearance(){String[] modes={"Dark","Light"};new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(modes,prefs.getBoolean("dark_theme",true)?0:1,(d,w)->{prefs.edit().putBoolean("dark_theme",w==0).apply();recreate();}).setNegativeButton("Cancel",null).show();}
    private String autoOffLabel(int m){return m==0?"Never":m+(m==1?" minute":" minutes");}
    private boolean hasPermission(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    private void toggleTorch(){if(!hasPermission()){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);return;}if(cameraId==null)refreshCamera();if(cameraId==null){Toast.makeText(this,"No flashlight available",Toast.LENGTH_SHORT).show();return;}if(!cameraAvailable){Toast.makeText(this,"Flashlight temporarily unavailable",Toast.LENGTH_SHORT).show();return;}setTorch(!isOn);}
    private void setTorch(boolean on){if(cameraManager==null||cameraId==null||!hasPermission())return;try{if(on&&Build.VERSION.SDK_INT>=33&&maxStrength>1)cameraManager.turnOnTorchWithStrengthLevel(cameraId,Math.max(1,Math.min(strength,maxStrength)));else cameraManager.setTorchMode(cameraId,on);isOn=on;prefs.edit().putBoolean("torch_on",on).apply();if(on)startAutoOff();else cancelTimer();renderState();updateRemoteControls();}catch(CameraAccessException|IllegalArgumentException|SecurityException e){if(!on){isOn=false;cancelTimer();}Toast.makeText(this,"Could not change flashlight state",Toast.LENGTH_SHORT).show();}}
    private void refreshCamera(){if(cameraManager==null||!hasPermission()){cameraId=null;cameraAvailable=false;maxStrength=1;renderState();return;}try{String found=null;int maximum=1;for(String id:cameraManager.getCameraIdList()){CameraCharacteristics c=cameraManager.getCameraCharacteristics(id);if(Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))){Integer facing=c.get(CameraCharacteristics.LENS_FACING);if(facing==null||facing==CameraCharacteristics.LENS_FACING_BACK){found=id;if(Build.VERSION.SDK_INT>=33){Integer m=c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);if(m!=null)maximum=Math.max(1,m);}break;}}}if(found==null){cameraId=null;cameraAvailable=false;isOn=false;}else{if(!found.equals(cameraId)){cameraId=found;isOn=prefs.getBoolean("torch_on",false);}maxStrength=maximum;cameraAvailable=true;}strength=Math.min(Math.max(1,strength),maxStrength);if(intensitySeek!=null){intensitySeek.setMax(Math.max(1,maxStrength-1));intensitySeek.setProgress(strength-1);intensitySeek.setEnabled(maxStrength>1);}updateIntensityLabel();renderState();}catch(CameraAccessException|SecurityException e){cameraAvailable=false;renderState();}}
    private void registerTorchCallback(){if(cameraManager!=null&&!callbackRegistered&&hasPermission())try{cameraManager.registerTorchCallback(torchCallback,handler);callbackRegistered=true;}catch(SecurityException ignored){}}
    private void startAutoOff(){cancelTimer();int minutes=prefs.getInt("auto_off",10);if(minutes>0&&isOn){offAtElapsed=SystemClock.elapsedRealtime()+minutes*60000L;handler.post(timerTick);}}
    private void cancelTimer(){handler.removeCallbacks(timerTick);offAtElapsed=0;if(timerView!=null&&!isOn)timerView.setText("Your flashlight is ready");}
    private void renderState(){if(powerButton==null||statusView==null||timerView==null)return;if(!hasPermission()){powerButton.setEnabled(true);powerIcon.setImageResource(R.drawable.ic_flash_off);statusView.setText("PERMISSION NEEDED");timerView.setText("Tap to allow camera access");}else if(cameraId==null){powerButton.setEnabled(false);powerIcon.setImageResource(R.drawable.ic_flash_off);statusView.setText("NO FLASHLIGHT FOUND");timerView.setText("No rear flash detected");}else if(!cameraAvailable){powerButton.setEnabled(false);powerIcon.setImageResource(R.drawable.ic_flash_off);statusView.setText("TEMPORARILY UNAVAILABLE");timerView.setText("Try again in a moment");}else{powerButton.setEnabled(true);powerIcon.setImageResource(isOn?R.drawable.ic_flash_on:R.drawable.ic_flash_off);statusView.setText(sosActive?"SOS SIGNAL ACTIVE":isOn?"TORCH IS ON":"READY WHEN YOU ARE");if(!isOn)timerView.setText("Your flashlight is ready");else if(offAtElapsed==0&&!sosActive)timerView.setText("Tap to turn off · no timer");}powerButton.setBackground(round(isOn?Color.rgb(255,214,130):ACCENT,dp(1000)));}
    private void updateIntensityLabel(){if(intensityValue!=null)intensityValue.setText(maxStrength>1?strength+" / "+maxStrength:"Standard");}
    private void applyKeepScreenOn(){if(prefs.getBoolean("keep_awake",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void updateRemoteControls(){if(this==null)return;sendBroadcast(new Intent(this,TorchWidgetProvider.class).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE));if(Build.VERSION.SDK_INT>=24)TileService.requestListeningState(this,new ComponentName(this,TorchTileService.class));}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==CAMERA_PERMISSION_REQUEST){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){registerTorchCallback();refreshCamera();if(!sosActive)setTorch(true);}else Toast.makeText(this,"Camera permission is required",Toast.LENGTH_LONG).show();}}
    private View actionButton(String text,int surface,int fg,View.OnClickListener click){TextView b=label(text,14,fg,true);b.setGravity(Gravity.CENTER);b.setBackground(round(surface,dp(16)));b.setOnClickListener(click);return b;}
    private LinearLayout card(int color){LinearLayout v=new LinearLayout(this);v.setBackground(round(color,dp(24)));return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private TextView label(String text,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void divider(LinearLayout parent,boolean dark){View line=new View(this);line.setBackgroundColor(dark?Color.rgb(39,53,72):Color.rgb(229,235,241));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.leftMargin=dp(16);lp.rightMargin=dp(16);parent.addView(line,lp);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
}
