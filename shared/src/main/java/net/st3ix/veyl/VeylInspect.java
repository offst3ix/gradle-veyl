package net.st3ix.veyl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks the server/worker to inspect a JAR and suggest keep rules (no local runtime). */
public final class VeylInspect {
  private static final Pattern KEEP_OBJ =
      Pattern.compile(
          "\\{\\s*\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*\"reason\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
  private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

  private VeylInspect() {}

  public static final class Suggestion {
    public final String name;
    public final String reason;

    public Suggestion(String name, String reason) {
      this.name = name;
      this.reason = reason == null ? "" : reason;
    }
  }

  public static final class Report {
    public final String kind;
    public final List<Suggestion> suggestedKeeps;
    public final String rawJson;

    public Report(String kind, List<Suggestion> suggestedKeeps, String rawJson) {
      this.kind = kind == null ? "other" : kind;
      this.suggestedKeeps = suggestedKeeps;
      this.rawJson = rawJson;
    }
  }

  public static Report inspect(Path inputJar) throws IOException {
    if (!Files.isRegularFile(inputJar)) {
      throw new IOException("Input JAR missing: " + inputJar);
    }
    VeylCredentials creds =
        VeylCredentials.load()
            .orElseThrow(
                () ->
                    new VeylAuthException(
                        VeylAuthException.Kind.NOT_SIGNED_IN,
                        "Not signed in to Veyl.\n  -> Run:  veyl login"));
    return inspect(creds, inputJar, "cli");
  }

  /** @deprecated local runtime removed — use {@link #inspect(Path)} */
  @Deprecated
  public static Report inspect(Path runtimeJarIgnored, Path inputJar)
      throws IOException, InterruptedException {
    return inspect(inputJar);
  }

  public static Report inspect(VeylCredentials creds, Path inputJar, String client)
      throws IOException {
    String boundary = "veyl-" + UUID.randomUUID();
    byte[] jarBytes = Files.readAllBytes(inputJar);
    String filename = inputJar.getFileName().toString();
    String who = client == null || client.isBlank() ? "cli" : client;

    HttpURLConnection conn =
        (HttpURLConnection) URI.create(creds.apiUrl + "/api/jobs/inspect").toURL().openConnection();
    conn.setRequestMethod("POST");
    conn.setConnectTimeout(30_000);
    conn.setReadTimeout(120_000);
    conn.setDoOutput(true);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "veyl-" + who + "/1.0");
    conn.setRequestProperty("X-Veyl-Client", who);
    conn.setRequestProperty("Authorization", "Bearer " + creds.token);
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

    try (OutputStream out = conn.getOutputStream()) {
      String head =
          "--"
              + boundary
              + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
              + filename.replace("\"", "")
              + "\"\r\nContent-Type: application/java-archive\r\n\r\n";
      out.write(head.getBytes(StandardCharsets.UTF_8));
      out.write(jarBytes);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    String body = readBody(conn);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      String err = null;
      Matcher m = ERROR.matcher(body == null ? "" : body);
      if (m.find()) err = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
      throw new IOException(
          "Inspect failed (HTTP "
              + code
              + "): "
              + (err == null || err.isBlank() ? body : err));
    }
    return parse(body);
  }

  public static Report parse(String json) {
    List<Suggestion> keeps = new ArrayList<Suggestion>();
    if (json == null || json.isBlank()) {
      return new Report("other", keeps, json == null ? "" : json);
    }
    String kind = stringField(json, "kind");
    Matcher m = KEEP_OBJ.matcher(json);
    Map<String, Suggestion> unique = new LinkedHashMap<String, Suggestion>();
    while (m.find()) {
      String name = unescape(m.group(1));
      String reason = unescape(m.group(2));
      if (name != null && !name.isBlank()) {
        unique.putIfAbsent(name, new Suggestion(name, reason));
      }
    }
    keeps.addAll(unique.values());
    return new Report(kind, keeps, json);
  }

  /** Missing recommended keeps (not already covered by configured keeps). */
  public static List<Suggestion> missing(Report report, List<String> configured) {
    Set<String> have = new LinkedHashSet<String>();
    if (configured != null) {
      for (String c : configured) {
        if (c != null && !c.isBlank()) have.add(c.trim());
      }
    }
    List<Suggestion> out = new ArrayList<Suggestion>();
    for (Suggestion s : report.suggestedKeeps) {
      if (!VeylKeepRules.covers(have, s.name)) out.add(s);
    }
    return out;
  }

  public static String formatRulesFile(Report report, List<String> existing) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Veyl keep rules - classes/packages that must not be renamed\n");
    sb.append("# Docs: http://docs.st3ix.net/docs/plugins/veyl/keep-rules\n");
    sb.append("# Detected kind: ").append(report.kind).append("\n\n");
    if (report.suggestedKeeps.isEmpty()) {
      sb.append("# (no suggestions - add keeps manually)\n");
    } else {
      for (Suggestion s : report.suggestedKeeps) {
        sb.append(s.name);
        if (!s.reason.isBlank()) sb.append("  # ").append(s.reason);
        sb.append('\n');
      }
    }
    if (existing != null) {
      for (String e : existing) {
        if (e != null && !e.isBlank()) sb.append(e.trim()).append('\n');
      }
    }
    return sb.toString();
  }

  private static String stringField(String json, String key) {
    Matcher m =
        Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
    return m.find() ? unescape(m.group(1)) : null;
  }

  private static String unescape(String value) {
    if (value == null) return null;
    return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
  }

  private static String readBody(HttpURLConnection conn) throws IOException {
    InputStream stream;
    try {
      stream = conn.getInputStream();
    } catch (IOException e) {
      stream = conn.getErrorStream();
      if (stream == null) throw e;
    }
    try (InputStream in = stream) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
