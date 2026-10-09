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
        tts.setLanguage(Locale.UK); // an Anki card may have switched the voice to another language
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "card");
    }

    /** Anki text-to-speech, "tts:en_US:the text": says it in that language (the phone's default if it has no such voice). */
    static void say(String ref) {
        int i = ref.indexOf(':', 4);
        if (i < 0 || !ready) return;
        String lang = ref.substring(4, i).replace('_', '-');
        if (!lang.isEmpty()) {
            int r = tts.setLanguage(Locale.forLanguageTag(lang));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
        }
        tts.speak(ref.substring(i + 1), TextToSpeech.QUEUE_FLUSH, null, "anki-tts");
    }

    static void stop() {
        pending = null;
        if (ready) tts.stop();
    }
}
