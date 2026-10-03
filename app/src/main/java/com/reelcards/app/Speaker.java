package com.reelcards.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Voiceover: reads the hook and question aloud when a card appears, the way creators use
 * text-to-speech on reels. Uses the phone's own offline voice; silent if none is installed.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;

    static void init(Context ctx) {
        if (tts != null) return;
        tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) return;
                int r = tts.setLanguage(Locale.UK);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.08f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                ready = true;
                if (pending != null) { speak(pending); pending = null; }
            }
        });
    }

    static void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready) { pending = text; return; }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card");
    }

    static void stop() {
        pending = null;
        if (ready) tts.stop();
    }
}
