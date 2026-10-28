import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

/** Stands in for opencode in the tests: reads the prompt from stdin and answers like the model would. */
public class FakeOpencode {
    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public static void main(String[] args) throws Exception {
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String log = System.getenv("FAKE_OC_LOG");
        if (log != null) {
            Files.writeString(Path.of(log), q(input) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        String answer;
        if (input.contains("GitLab CI job failed")) {
            Matcher m = Pattern.compile("Job: \"(\\w+)\"").matcher(input);
            String job = m.find() ? m.group(1) : "?";
            String edits = job.equals("test") ? "[{\"file\":\"src/a.ts\",\"startLine\":4,\"endLine\":4,\"newText\":\"// four fixed\"}]" : "[]";
            answer = "{\"cause\":" + q("The " + job + " job fails because line 4 is wrong.") + ",\"explanation\":\"fix line 4\",\"edits\":" + edits + "}";
        } else if (input.contains("propose a code change")) {
            Matcher m = Pattern.compile("Note is on line: (\\d+)").matcher(input);
            m.find();
            String line = m.group(1);
            answer = "{\"explanation\":\"fix line " + line + "\",\"edits\":[{\"file\":\"src/a.ts\",\"startLine\":" + line + ",\"endLine\":" + line + ",\"newText\":" + q("const v" + line + " = 'fixed';") + "}]}";
        } else if (input.contains("write the commit message")) {
            answer = input.contains("previous answer")
                ? "{\"type\":\"feat\",\"scope\":\"api\",\"subject\":\"Add login flow.\",\"body\":\"Adds the login flow so users can sign in with the new token format and keeps the old one.\",\"breaking\":\"\"}"
                : "{\"type\":\"feature\",\"scope\":\"api\",\"subject\":\"Add login flow.\",\"body\":\"x\",\"breaking\":\"\"}";
        } else if (input.contains("write a reply")) {
            answer = "{\"reply\":\"Good point, I changed it.\"}";
        } else {
            answer = "{\"findings\":[{\"file\":\"src/a.ts\",\"line\":3,\"severity\":\"High\",\"comment\":\"Possible null access.\"},{\"file\":\"../evil\",\"line\":1,\"comment\":\"x\"}]}";
        }
        String text = "Here you go:\n```json\n" + answer + "\n```";
        System.out.println("{\"type\":\"step_start\",\"part\":{}}");
        System.out.println("{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":" + q(text) + "}}");
        System.out.println("{\"type\":\"step_finish\",\"part\":{}}");
    }
}
