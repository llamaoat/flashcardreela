import com.reelcards.engine.Card;
import com.reelcards.engine.NotesParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Prints the "Make cards with AI" prompt and the cards the Java notes parser makes from notes.txt, for
 * comparing with notes.js. Usage: java -cp <engine classes>:<this> Notes tools/crosscheck/notes.txt
 */
public class Notes {
    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder("prompt " + NotesParser.AI_PROMPT.replace("\n", "\\n") + "\n");
        NotesParser.Result r = NotesParser.parse(text, "My notes");
        for (Card c : r.cards) out.append(c.id).append('\t').append(c.front).append('\t').append(c.back).append('\t').append(c.extra).append('\n');
        out.append("skipped ").append(r.skippedLines).append('\n');
        new java.io.PrintStream(System.out, true, "UTF-8").print(out); // UTF-8 whatever the console's encoding
    }
}
