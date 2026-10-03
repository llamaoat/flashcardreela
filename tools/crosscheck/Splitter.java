import com.reelcards.engine.AnswerShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints how the Java engine reads the shape of each card in corpus.json (one line per card), for
 * comparing with splitter.js. Usage: java -cp <engine classes>:<this> Splitter tools/crosscheck/corpus.json
 */
public class Splitter {
    public static void main(String[] args) throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (List<String> r : rows(s)) {
            AnswerShape x = AnswerShape.of(r.get(0), r.get(1), r.get(2));
            out.append(x.kind).append(" | ").append(String.join(" ¦ ", x.points))
               .append(" | over=").append(String.join(" ¦ ", x.over))
               .append(" | ctx=").append(x.context).append(" | main=").append(x.questionMain)
               .append(" | a2=").append(x.inlineExplanation).append(" | more=").append(x.more)
               .append(" | starts=").append(x.multi() ? String.join(",", AnswerShape.starts(x.points)) : "").append('\n');
        }
        new java.io.PrintStream(System.out, true, "UTF-8").print(out); // UTF-8 whatever the console's encoding
    }

    /** A JSON array of arrays of strings (all corpus.json holds). */
    static List<List<String>> rows(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = null;
        StringBuilder b = null;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    char n = s.charAt(++i);
                    if (n == 'n') b.append('\n');
                    else if (n == 'u') { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                    else b.append(n);
                } else if (c == '"') { inString = false; cur.add(b.toString()); }
                else b.append(c);
            } else if (c == '"') { inString = true; b = new StringBuilder(); }
            else if (c == '[' && i > 0) cur = new ArrayList<>();
            else if (c == ']' && cur != null) { rows.add(cur); cur = null; }
        }
        return rows;
    }
}
