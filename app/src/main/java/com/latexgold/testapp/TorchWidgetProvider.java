package com.latexgold.testapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class TorchWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "flashlight_settings";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        boolean on=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("torch_on",false);
        for(int id:ids){RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.torch_widget);views.setTextViewText(R.id.widget_label,on?"TORCH ON":"TURN ON");views.setImageViewResource(R.id.widget_icon,on?R.drawable.ic_flash_on:R.drawable.ic_flash_off);Intent launch=new Intent(context,MainActivity.class).setAction("com.latexgold.testapp.WIDGET_TOGGLE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent pi=PendingIntent.getActivity(context,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);views.setOnClickPendingIntent(R.id.widget_root,pi);manager.updateAppWidget(id,views);}
    }
}
