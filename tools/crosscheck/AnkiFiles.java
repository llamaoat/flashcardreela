import com.reelcards.engine.Card;
import com.reelcards.engine.Occlusion;
import com.reelcards.engine.anki.AnkiPackage;

import java.io.File;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Prints every card the Java importer reads from an .apkg (one tab-separated line per card, then the
 * stats), for comparing with anki-files.js. Usage: java -cp <engine classes>:<this> AnkiFiles deck.apkg
 */
public class AnkiFiles {
    static final long NOW = 1759400000000L; // fixed, so due dates of new cards match

    static String f(double v) { return String.format(Locale.ROOT, "%.5f", v); }

    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        AnkiPackage.Result r;
        try (ZipFile z = new ZipFile(file)) { r = AnkiPackage.read(z, file.getName(), NOW, null); }
        StringBuilder b = new StringBuilder();
        for (Card c : r.cards) {
            String io = "";
            if (c.occlusion != null) {
                StringBuilder s = new StringBuilder(c.occlusionTarget + "|");
                for (Occlusion.Shape sh : Occlusion.parse(c.occlusion)) {
                    s.append(sh.kind.charAt(0)).append(':').append(sh.ord).append(':').append(sh.oi ? 1 : 0).append(':');
                    if (sh.pts != null) for (int i = 0; i + 1 < sh.pts.length; i += 2) s.append(f(sh.pts[i])).append(',').append(f(sh.pts[i + 1])).append(' ');
                    else s.append(f(sh.x)).append(',').append(f(sh.y)).append(',').append(f(sh.w)).append(',').append(f(sh.h));
                    s.append(';');
                }
                io = s.toString();
            }
            boolean rev = c.state == Card.REVIEW;
            b.append(String.join("\t", c.id, c.deck, c.front, c.back, c.extra,
                    c.image == null ? "" : c.image, "" + c.imageW, "" + c.imageH,
                    c.answerImage == null ? "" : c.answerImage, "" + c.answerImageW, "" + c.answerImageH,
                    c.cloze ? "1" : "0", io, rev ? "review" : "new",
                    rev ? f(c.stability) : "", rev ? f(c.difficulty) : "", rev ? "" + c.due : "", rev ? "" + c.lastReview : "",
                    "" + c.reps, "" + c.lapses, c.audio == null ? "" : c.audio, c.answerAudio == null ? "" : c.answerAudio).replace("\n", "\\n")).append('\n');
        }
        AnkiPackage.Stats s = r.stats;
        b.append("stats ").append(s.cards).append(' ').append(s.imported).append(' ').append(s.suspended).append(' ').append(s.audio)
         .append(' ').append(s.empty).append(' ').append(s.unknown).append(' ').append(s.images).append(' ').append(s.cloze)
         .append(' ').append(s.occlusion).append(' ').append(s.kept).append(' ').append(s.sounds).append('\n');
        new java.io.PrintStream(System.out, true, "UTF-8").print(b); // UTF-8 whatever the console's encoding
    }
}
