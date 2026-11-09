/*
 * OpenRouter + Groq + Google AI Studio Chat CLI (Java)
 * ----------------------------------------------------
 * Terminal-chat met modellen van OpenRouter, Groq en Google AI Studio.
 * Geen extra libraries nodig. Vereist Java 11 of nieuwer.
 *
 * Groq-modellen voeg je toe met "groq:" ervoor, bv. groq:llama-3.3-70b-versatile.
 * Google AI Studio-modellen met "gemini:" ervoor, bv. gemini:gemini-2.5-flash.
 * Alle andere model-id's gaan naar OpenRouter.
 *
 * Start (zonder compileren):   java OpenRouterChat.java
 */

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public class OpenRouterChat {

    // Providers: {prefix, url, config-veld, key-commando, naam, hint}. OpenRouter (zonder prefix) als eerste.
    static final String[][] PROVIDERS = {
        { "", "https://openrouter.ai/api/v1/chat/completions", "api_key", "/key", "OpenRouter API key", "sk-or-..." },
        { "groq:", "https://api.groq.com/openai/v1/chat/completions", "groq_key", "/groqkey", "Groq API key", "gsk_..." },
        { "gemini:", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
          "gemini_key", "/geminikey", "Google AI Studio API key", "AQ.... of AIza..." },
    };
    static final Path CONFIG_PATH = System.getenv("OPENROUTER_CHAT_CONFIG") != null
            ? Paths.get(System.getenv("OPENROUTER_CHAT_CONFIG"))
            : Paths.get(System.getProperty("user.home"), ".openrouter_chat.json");

    static final String RESET = "\033[0m", DIM = "\033[2m", BOLD = "\033[1m",
            CYAN = "\033[36m", GREEN = "\033[32m", YELLOW = "\033[33m", RED = "\033[31m";

    static final String HELP = "\n" + BOLD + "Commando's:" + RESET + "\n"
            + "  /models            toon je opgeslagen modellen\n"
            + "  /zoek              modellen zoeken en kiezen uit een lijst (OpenRouter gratis, Groq, Google)\n"
            + "  /add <model-id>    model handmatig toevoegen (meerdere tegelijk mag, gescheiden door spatie/komma)\n"
            + "                     \"groq:\" = Groq, \"gemini:\" = Google AI Studio, anders OpenRouter\n"
            + "  /use <nr|id>       wissel van model (nummer uit /models of het id)\n"
            + "  /remove <nr|id>    model verwijderen\n"
            + "  /system <tekst>    system prompt instellen (leeg = verwijderen)\n"
            + "  /clear             gesprek wissen (nieuw gesprek)\n"
            + "  /key               OpenRouter API key instellen\n"
            + "  /groqkey           Groq API key instellen\n"
            + "  /geminikey         Google AI Studio API key instellen\n"
            + "  /help              deze hulp\n"
            + "  /quit              afsluiten\n";

    // ---------- config ----------
    static Map<String, String> keys = new LinkedHashMap<>(); // config-veld -> key
    static List<String> models = new ArrayList<>();
    static String current = null;
    static String systemPrompt = "";

    static BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    static PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    static HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    @SuppressWarnings("unchecked")
    static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            Object o = Json.parse(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            Map<String, Object> m = (Map<String, Object>) o;
            for (String[] p : PROVIDERS)
                if (m.get(p[2]) instanceof String) keys.put(p[2], (String) m.get(p[2]));
            if (m.get("models") instanceof List)
                for (Object x : (List<Object>) m.get("models")) if (x instanceof String) models.add((String) x);
            if (m.get("current") instanceof String) current = (String) m.get("current");
            if (m.get("system") instanceof String) systemPrompt = (String) m.get("system");
        } catch (Exception ignored) { }
    }

    static void saveConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String[] p : PROVIDERS) m.put(p[2], key(p[2]));
        m.put("models", models);
        m.put("current", current);
        m.put("system", systemPrompt);
        try {
            Files.writeString(CONFIG_PATH, Json.write(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            out.println(RED + "Kon config niet opslaan: " + e.getMessage() + RESET);
        }
    }

    static String prompt(String text) throws IOException {
        out.print(text);
        out.flush();
        return in.readLine(); // null = einde invoer
    }

    /** Vraagt een API key. Lege invoer = overslaan. */
    static void askKey(String[] p) throws IOException {
        String k = prompt(YELLOW + "Plak je " + p[4] + " (" + p[5] + "), of Enter om over te slaan: " + RESET);
        if (k != null && !k.trim().isEmpty()) {
            keys.put(p[2], k.trim());
            saveConfig();
            out.println(GREEN + p[4] + " opgeslagen." + RESET);
        }
    }

    static String key(String field) { return keys.getOrDefault(field, ""); }

    /** Geeft {url, key, model-id, key-commando} voor het actieve model. */
    static String[] route() {
        for (String[] p : PROVIDERS)
            if (!p[0].isEmpty() && current.startsWith(p[0]))
                return new String[] { p[1], key(p[2]), current.substring(p[0].length()), p[3] };
        String[] p = PROVIDERS[0];
        return new String[] { p[1], key(p[2]), current, p[3] };
    }

    static void addModels(String text) {
        List<String> added = new ArrayList<>();
        if (text != null)
            for (String p : text.split("[,\\s]+")) {
                p = p.trim();
                if (!p.isEmpty() && !models.contains(p)) { models.add(p); added.add(p); }
            }
        if (current == null && !models.isEmpty()) current = models.get(0);
        saveConfig();
        if (added.isEmpty()) out.println(DIM + "Niets nieuws toegevoegd." + RESET);
        else out.println(GREEN + "Toegevoegd: " + String.join(", ", added) + RESET);
    }

    static String resolveModel(String arg) {
        arg = arg.trim();
        if (arg.matches("\\d+")) {
            int i = Integer.parseInt(arg) - 1;
            return (i >= 0 && i < models.size()) ? models.get(i) : null;
        }
        return models.contains(arg) ? arg : null;
    }

    static void showModels() {
        if (models.isEmpty()) { out.println(DIM + "Nog geen modellen. Gebruik /add <model-id>." + RESET); return; }
        for (int i = 0; i < models.size(); i++) {
            String mark = models.get(i).equals(current) ? GREEN + "*" + RESET : " ";
            out.println(" " + mark + " " + (i + 1) + ". " + models.get(i));
        }
    }

    // ---------- modellen zoeken ----------
    /** GET-verzoek; geeft het geparste antwoord terug of gooit een IOException met een leesbare melding. */
    @SuppressWarnings("unchecked")
    static Object getJson(String url, String headerName, String headerValue) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET();
        if (headerName != null) b.header(headerName, headerValue);
        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("afgebroken");
        }
        if (resp.statusCode() != 200) {
            String msg = resp.body();
            try {
                Object j = Json.parse(resp.body());
                if (j instanceof List && !((List<Object>) j).isEmpty()) j = ((List<Object>) j).get(0);
                Object e = ((Map<String, Object>) j).get("error");
                if (e instanceof Map && ((Map<String, Object>) e).get("message") != null)
                    msg = String.valueOf(((Map<String, Object>) e).get("message"));
            } catch (Exception ignored) { }
            throw new IOException("HTTP " + resp.statusCode() + ": " + msg);
        }
        return Json.parse(resp.body());
    }

    static String kContext(Object n) {
        return (n instanceof Number && ((Number) n).doubleValue() > 0)
                ? Math.round(((Number) n).doubleValue() / 1000) + "k context" : "";
    }

    static boolean isZero(Object v) {
        try { return v != null && Double.parseDouble(String.valueOf(v)) == 0; } catch (NumberFormatException e) { return false; }
    }

    /** Geeft een lijst van {id, info} terug (id inclusief prefix). */
    @SuppressWarnings("unchecked")
    static List<String[]> fetchModels(String provider) throws IOException {
        List<String[]> outList = new ArrayList<>();
        if (provider.equals("openrouter")) {
            Map<String, Object> j = (Map<String, Object>) getJson("https://openrouter.ai/api/v1/models", null, null);
            for (Object o : (List<Object>) j.get("data")) {
                Map<String, Object> m = (Map<String, Object>) o;
                String id = String.valueOf(m.get("id"));
                Object pr = m.get("pricing");
                boolean free = id.endsWith(":free") || (pr instanceof Map
                        && isZero(((Map<String, Object>) pr).get("prompt")) && isZero(((Map<String, Object>) pr).get("completion")));
                Object arch = m.get("architecture");
                Object outMod = arch instanceof Map ? ((Map<String, Object>) arch).get("output_modalities") : null;
                boolean chat = !(outMod instanceof List && ((List<Object>) outMod).contains("audio")); // geen muziek/spraak
                if (free && chat) outList.add(new String[] { id, kContext(m.get("context_length")) });
            }
        } else if (provider.equals("groq")) {
            Map<String, Object> j = (Map<String, Object>) getJson("https://api.groq.com/openai/v1/models",
                    "Authorization", "Bearer " + key("groq_key"));
            for (Object o : (List<Object>) j.get("data")) {
                Map<String, Object> m = (Map<String, Object>) o;
                String id = String.valueOf(m.get("id"));
                if (Boolean.FALSE.equals(m.get("active"))) continue;
                if (id.toLowerCase().matches(".*(whisper|tts|playai|orpheus|guard).*")) continue; // alleen chat
                outList.add(new String[] { "groq:" + id, kContext(m.get("context_window")) });
            }
        } else { // Google AI Studio
            Map<String, Object> j = (Map<String, Object>) getJson(
                    "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                    "x-goog-api-key", key("gemini_key"));
            Object list = j.get("models");
            if (list instanceof List)
                for (Object o : (List<Object>) list) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    String name = String.valueOf(m.get("name"));
                    Object methods = m.get("supportedGenerationMethods");
                    if (!(methods instanceof List && ((List<Object>) methods).contains("generateContent"))) continue;
                    if (name.toLowerCase().matches(".*(embedding|aqa|tts|image).*")) continue;
                    if (name.startsWith("models/")) name = name.substring(7);
                    outList.add(new String[] { "gemini:" + name, kContext(m.get("inputTokenLimit")) });
                }
        }
        outList.sort(Comparator.comparing(a -> a[0]));
        return outList;
    }

    /** "1,3,5" / "2-4" / "alle" -> gesorteerde lijst met indexen. */
    static List<Integer> parseSelection(String text, int n) {
        TreeSet<Integer> set = new TreeSet<>();
        if (text != null)
            for (String t : text.toLowerCase().split("[,\\s]+")) {
                if (t.isEmpty()) continue;
                if (t.equals("a") || t.equals("alle") || t.equals("all")) { for (int i = 0; i < n; i++) set.add(i); continue; }
                if (t.matches("\\d+-\\d+")) {
                    String[] ab = t.split("-");
                    for (int i = Integer.parseInt(ab[0]); i <= Integer.parseInt(ab[1]); i++) if (i >= 1 && i <= n) set.add(i - 1);
                    continue;
                }
                if (t.matches("\\d+")) { int i = Integer.parseInt(t); if (i >= 1 && i <= n) set.add(i - 1); }
            }
        return new ArrayList<>(set);
    }

    static void browseModels() throws IOException {
        out.println("\n" + BOLD + "Waar wil je modellen zoeken?" + RESET);
        out.println("  1. OpenRouter " + DIM + "(alleen gratis modellen)" + RESET);
        out.println("  2. Groq " + DIM + "(gratis met limieten)" + RESET);
        out.println("  3. Google AI Studio " + DIM + "(meeste modellen gratis met limieten)" + RESET);
        String c = prompt("Kies 1-3 (Enter = annuleren): ");
        c = c == null ? "" : c.trim();
        int pi = c.equals("1") ? 0 : c.equals("2") ? 1 : c.equals("3") ? 2 : -1;
        if (pi < 0) return;
        String[] p = PROVIDERS[pi];
        String provider = pi == 0 ? "openrouter" : pi == 1 ? "groq" : "gemini";

        if (pi != 0 && key(p[2]).isEmpty()) {
            out.println(YELLOW + "Hiervoor heb je eerst een " + p[4] + " nodig." + RESET);
            askKey(p);
            if (key(p[2]).isEmpty()) return;
        }

        out.println(DIM + "Modellen ophalen..." + RESET);
        List<String[]> list;
        try {
            list = fetchModels(provider);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            out.println(RED + "Ophalen mislukt: " + msg + RESET);
            if (pi != 0 && msg.toLowerCase().matches("(?s).*(40[013]|api key).*"))
                out.println(YELLOW + "Klopt je key? Gebruik " + p[3] + " om hem opnieuw in te stellen." + RESET);
            return;
        }
        if (list.isEmpty()) { out.println(DIM + "Geen modellen gevonden." + RESET); return; }

        for (int i = 0; i < list.size(); i++) {
            String have = models.contains(list.get(i)[0]) ? " " + GREEN + "(al toegevoegd)" + RESET : "";
            out.println(String.format("  %2d. %s %s%s%s%s", i + 1, list.get(i)[0], DIM, list.get(i)[1], RESET, have));
        }
        List<Integer> idx = parseSelection(prompt("Welke wil je toevoegen? (bv. 1,3 of 2-5 of \"alle\"; Enter = geen): "), list.size());
        if (idx.isEmpty()) { out.println(DIM + "Niets toegevoegd." + RESET); return; }
        StringBuilder sb = new StringBuilder();
        for (int i : idx) sb.append(list.get(i)[0]).append(' ');
        addModels(sb.toString());
    }

    // ---------- API ----------
    @SuppressWarnings("unchecked")
    static String streamChat(List<Map<String, Object>> messages) {
        String[] rt = route();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", rt[2]);
        body.put("messages", messages);
        body.put("stream", true);

        HttpRequest req = HttpRequest.newBuilder(URI.create(rt[0]))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + rt[1])
                .header("Content-Type", "application/json")
                .header("X-Title", "OpenRouter Chat CLI")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        StringBuilder full = new StringBuilder();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                if (resp.statusCode() != 200) {
                    StringBuilder err = new StringBuilder();
                    for (String l; (l = r.readLine()) != null; ) err.append(l);
                    String msg = "";
                    try {
                        Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) Json.parse(err.toString())).get("error");
                        msg = String.valueOf(e.get("message"));
                    } catch (Exception ignored) { msg = err.toString(); }
                    out.println(RED + "HTTP " + resp.statusCode() + ": " + msg + RESET);
                    if (resp.statusCode() == 401 || resp.statusCode() == 403 || msg.toLowerCase().contains("api key"))
                        out.println(YELLOW + "Je API key klopt niet. Gebruik " + rt[3] + " om hem opnieuw in te stellen." + RESET);
                    return null;
                }
                for (String line; (line = r.readLine()) != null; ) {
                    line = line.trim();
                    if (!line.startsWith("data:")) continue; // keep-alives
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) break;
                    Map<String, Object> chunk;
                    try { chunk = (Map<String, Object>) Json.parse(data); } catch (Exception e) { continue; }
                    if (chunk.containsKey("error")) {
                        Object e = chunk.get("error");
                        Object msg = (e instanceof Map) ? ((Map<String, Object>) e).get("message") : e;
                        out.println("\n" + RED + "Fout: " + msg + RESET);
                        return null;
                    }
                    Object choices = chunk.get("choices");
                    if (!(choices instanceof List) || ((List<Object>) choices).isEmpty()) continue;
                    Object delta = ((Map<String, Object>) ((List<Object>) choices).get(0)).get("delta");
                    if (!(delta instanceof Map)) continue;
                    Object piece = ((Map<String, Object>) delta).get("content");
                    if (piece instanceof String && !((String) piece).isEmpty()) {
                        full.append(piece);
                        out.print(piece);
                        out.flush();
                    }
                }
            }
        } catch (IOException e) {
            out.println(RED + "Verbindingsfout: " + e.getMessage() + RESET);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        out.println();
        return full.toString();
    }

    // ---------- main loop ----------
    public static void main(String[] args) throws IOException {
        loadConfig();
        out.println(BOLD + CYAN + "=== OpenRouter + Groq + Google AI Studio Chat CLI ===" + RESET + "  (typ /help voor commando's)\n");

        boolean anyKey = false;
        for (String[] p : PROVIDERS) if (!key(p[2]).isEmpty()) anyKey = true;
        if (!anyKey) for (String[] p : PROVIDERS) askKey(p);
        if (models.isEmpty()) {
            out.println("\n" + YELLOW + "Je hebt nog geen modellen. Laten we er een paar zoeken." + RESET);
            browseModels();
        }

        List<Map<String, Object>> history = new ArrayList<>();

        while (true) {
            String model = current != null ? current : "geen model";
            String user = prompt("\n" + BOLD + GREEN + "jij" + RESET + " " + DIM + "[" + model + "]" + RESET + " > ");
            if (user == null) { out.println("\nDoei!"); break; }
            user = user.trim();
            if (user.isEmpty()) continue;

            // --- commando's ---
            if (user.startsWith("/")) {
                String[] parts = user.split(" ", 2);
                String cmd = parts[0].toLowerCase();
                String arg = parts.length > 1 ? parts[1] : "";
                switch (cmd) {
                    case "/quit": case "/exit": case "/q":
                        out.println("Doei!");
                        return;
                    case "/help": out.println(HELP); break;
                    case "/models": showModels(); break;
                    case "/zoek": case "/browse": browseModels(); break;
                    case "/add":
                        if (arg.trim().isEmpty()) arg = prompt("Model-id('s): ");
                        addModels(arg);
                        break;
                    case "/use": {
                        String m = resolveModel(arg);
                        if (m != null) {
                            current = m; saveConfig();
                            out.println(GREEN + "Nu actief: " + m + RESET + " " + DIM + "(gesprek blijft behouden)" + RESET);
                        } else out.println(RED + "Onbekend model. Zie /models." + RESET);
                        break;
                    }
                    case "/remove": {
                        String m = resolveModel(arg);
                        if (m != null) {
                            models.remove(m);
                            if (m.equals(current)) current = models.isEmpty() ? null : models.get(0);
                            saveConfig();
                            out.println("Verwijderd: " + m);
                        } else out.println(RED + "Onbekend model. Zie /models." + RESET);
                        break;
                    }
                    case "/system":
                        systemPrompt = arg.trim(); saveConfig();
                        out.println("System prompt " + (systemPrompt.isEmpty() ? "verwijderd." : "ingesteld."));
                        break;
                    case "/clear": history.clear(); out.println(DIM + "Gesprek gewist." + RESET); break;
                    case "/key": askKey(PROVIDERS[0]); break;
                    case "/groqkey": askKey(PROVIDERS[1]); break;
                    case "/geminikey": askKey(PROVIDERS[2]); break;
                    default: out.println(RED + "Onbekend commando. Typ /help." + RESET);
                }
                continue;
            }

            // --- chatten ---
            if (current == null) { out.println(RED + "Geen model. Gebruik /add <model-id>." + RESET); continue; }
            String[] r = route();
            if (r[1].isEmpty()) { out.println(RED + "Geen API key voor dit model. Gebruik " + r[3] + "." + RESET); continue; }

            history.add(msg("user", user));
            List<Map<String, Object>> messages = new ArrayList<>();
            if (!systemPrompt.isEmpty()) messages.add(msg("system", systemPrompt));
            messages.addAll(history);

            out.print(BOLD + CYAN + "ai" + RESET + " > ");
            out.flush();
            String reply = streamChat(messages);
            if (reply != null && !reply.isEmpty()) history.add(msg("assistant", reply));
            else history.remove(history.size() - 1); // mislukte vraag niet bewaren
        }
    }

    static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ---------- mini JSON (parse + write) ----------
    static class Json {
        private final String s;
        private int i = 0;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("Extra tekens na JSON");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("Onverwacht einde");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("':' verwacht");
                ws(); m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("',' of '}' verwacht");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("',' of ']' verwacht");
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("'\"' verwacht");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e); // " \ /
                }
            }
        }

        private Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("Onverwacht teken: " + s.charAt(i));
            return Double.parseDouble(s.substring(st, i));
        }

        static String write(Object o) {
            StringBuilder b = new StringBuilder();
            write(o, b);
            return b.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object o, StringBuilder b) {
            if (o == null) b.append("null");
            else if (o instanceof String) quote((String) o, b);
            else if (o instanceof Boolean || o instanceof Number) b.append(o);
            else if (o instanceof Map) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(e.getKey(), b); b.append(':'); write(e.getValue(), b);
                }
                b.append('}');
            } else if (o instanceof List) {
                b.append('[');
                boolean first = true;
                for (Object x : (List<Object>) o) {
                    if (!first) b.append(',');
                    first = false;
                    write(x, b);
                }
                b.append(']');
            } else quote(o.toString(), b);
        }

        private static void quote(String str, StringBuilder b) {
            b.append('"');
            for (int k = 0; k < str.length(); k++) {
                char c = str.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            b.append('"');
        }
    }
}
