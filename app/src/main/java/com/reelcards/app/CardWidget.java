package com.reelcards.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

import com.reelcards.engine.AnswerShape;
import com.reelcards.engine.Card;
import com.reelcards.engine.CopyWriter;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Grade;

import java.util.ArrayList;
import java.util.List;

/** Home-screen widget: one due card, with Show answer, then Knew it / Again. Grades go through
 *  Engine.gradeOutside, so the reel rhythm and format bandit aren't touched. Cards that need a picture
 *  or a sound stay out of the widget. RemoteViews need XML layouts (res/layout/card_widget.xml). */
public class CardWidget extends AppWidgetProvider {
    static final String REVEAL = "com.reelcards.app.widget.REVEAL";
    static final String KNEW = "com.reelcards.app.widget.KNEW";
    static final String AGAIN = "com.reelcards.app.widget.AGAIN";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) { draw(ctx); }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        SharedPreferences p = Store.prefs(ctx);
        if (REVEAL.equals(a)) {
            p.edit().putBoolean("widget_revealed", true).apply();
            draw(ctx);
        } else if (KNEW.equals(a) || AGAIN.equals(a)) {
            Engine e = Store.engine(ctx);
            Card c = e.find(p.getString("widget_card", ""));
            if (c != null) {
                e.gradeOutside(c, KNEW.equals(a) ? Grade.GOOD : Grade.AGAIN, System.currentTimeMillis());
                Store.saveNow(ctx);
            }
            p.edit().remove("widget_card").putBoolean("widget_revealed", false).apply();
            draw(ctx);
        } else {
            super.onReceive(ctx, intent);
        }
    }

    /** Redraws every Reelcards widget; call it after progress changes elsewhere too. */
    static void draw(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, CardWidget.class));
        if (ids == null || ids.length == 0) return;
        Engine e = Store.engine(ctx);
        SharedPreferences p = Store.prefs(ctx);
        long now = System.currentTimeMillis();
        Card c = e.find(p.getString("widget_card", ""));
        if (c == null || e.hidden.contains(c.id) || needsMedia(c) || (!c.isNew() && c.due > now)) {
            c = pick(e, now);
            p.edit().putString("widget_card", c == null ? "" : c.id).putBoolean("widget_revealed", false).apply();
        }
        boolean revealed = c != null && p.getBoolean("widget_revealed", false);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.card_widget);
        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.question, open);
        v.setOnClickPendingIntent(R.id.open, open);
        if (c == null) {
            v.setTextViewText(R.id.deck, "Reelcards");
            v.setTextViewText(R.id.question, "Nothing due right now. Nice.");
            v.setViewVisibility(R.id.answer, View.GONE);
            v.setViewVisibility(R.id.reveal, View.GONE);
            v.setViewVisibility(R.id.knew, View.GONE);
            v.setViewVisibility(R.id.again, View.GONE);
            v.setViewVisibility(R.id.open, View.VISIBLE);
        } else {
            v.setTextViewText(R.id.deck, CopyWriter.deckLabel(c.deck));
            v.setTextViewText(R.id.question, AnswerShape.plainText(c.front));
            v.setTextViewText(R.id.answer, AnswerShape.plainText(c.back).replaceAll("\\s*\\n+\\s*", " · "));
            v.setViewVisibility(R.id.answer, revealed ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.reveal, revealed ? View.GONE : View.VISIBLE);
            v.setViewVisibility(R.id.knew, revealed ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.again, revealed ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.open, View.GONE);
            v.setOnClickPendingIntent(R.id.reveal, action(ctx, REVEAL, 1));
            v.setOnClickPendingIntent(R.id.knew, action(ctx, KNEW, 2));
            v.setOnClickPendingIntent(R.id.again, action(ctx, AGAIN, 3));
        }
        mgr.updateAppWidget(ids, v);
    }

    private static PendingIntent action(Context ctx, String a, int code) {
        Intent i = new Intent(ctx, CardWidget.class).setAction(a);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean needsMedia(Card c) {
        return c.image != null || c.occlusion != null || (c.audio != null && !c.audio.startsWith("tts:"));
    }

    /** The engine's own pick, with cards the widget can't show held back for the moment. */
    private static Card pick(Engine e, long now) {
        List<String> held = new ArrayList<String>();
        for (Card c : e.cards) if (needsMedia(c) && e.hidden.add(c.id)) held.add(c.id);
        try { return e.pickForWidget(now); } finally { e.hidden.removeAll(held); }
    }
}
