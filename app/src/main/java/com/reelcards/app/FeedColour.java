package com.reelcards.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * "Match my feed's colours" (opt-in, Android 11+): the dominant hue of the reel the student was just
 * watching, so the next card's dark styles can be tinted to sit in the same colour world.
 *
 * Privacy: the screenshot is shrunk to 24x48 pixels in memory, reduced to one number (a hue from 0
 * to 360) and discarded straight away. Nothing is saved, and nothing leaves the phone.
 */
final class FeedColour {
    private FeedColour() {}

    /** Saturation-weighted circular mean hue of a small bitmap; NaN when the frame is mostly grey or black. */
    static float hue(Bitmap small) {
        double x = 0, y = 0, weight = 0;
        int n = 0;
        float[] hsv = new float[3];
        for (int j = 0; j < small.getHeight(); j++) {
            for (int i = 0; i < small.getWidth(); i++) {
                Color.colorToHSV(small.getPixel(i, j), hsv);
                double w = hsv[1] * hsv[2]; // vivid, bright pixels count most
                double a = Math.toRadians(hsv[0]);
                x += Math.cos(a) * w; y += Math.sin(a) * w; weight += w; n++;
            }
        }
        if (n == 0 || weight / n < 0.06) return Float.NaN;
        double h = Math.toDegrees(Math.atan2(y, x));
        return (float) ((h + 360) % 360);
    }

    /** Copies a (hardware) screenshot into a tiny software bitmap and returns its hue. */
    static float hueOfScreenshot(Bitmap shot) {
        Bitmap soft = null, small = null;
        try {
            soft = shot.getConfig() == Bitmap.Config.HARDWARE ? shot.copy(Bitmap.Config.ARGB_8888, false) : shot;
            if (soft == null) return Float.NaN;
            small = Bitmap.createScaledBitmap(soft, 24, 48, true);
            return hue(small);
        } catch (RuntimeException e) {
            return Float.NaN;
        } finally {
            if (small != null && small != soft) small.recycle();
            if (soft != null && soft != shot) soft.recycle();
        }
    }
}
