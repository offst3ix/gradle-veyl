package net.st3ix.veyl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads a JAR to the Veyl API; the worker obfuscates on the server. No local runtime.
 */
public final class VeylObfuscate {
  private static final Pattern JOB_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ERROR = Pattern.compile("\"error\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

  private VeylObfuscate() {}

  public static void run(Path inputJar, Path outputJar, String settingsJson) throws IOException {
    run(inputJar, outputJar, settingsJson, null, "cli");
  }

  public static void run(
      Path inputJar, Path outputJar, String settingsJson, Consumer<String> logLine, String client)
      throws IOException {
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
    String who = client == null || client.isBlank() ? "cli" : client;
    if (logLine != null) logLine.accept("Veyl - upload " + inputJar.getFileName());
    String jobId = uploadJob(creds, inputJar, settingsJson, who, logLine);
    if (logLine != null) logLine.accept("Veyl - download job " + jobId);
    downloadJob(creds, jobId, outputJar, who);
    if (logLine != null) logLine.accept("Veyl - wrote " + outputJar.toAbsolutePath());
  }

  /** @deprecated local runtime removed — use {@link #run(Path, Path, String, Consumer, String)} */
  @Deprecated
  public static void run(
      Path runtimeJarIgnored,
      Path inputJar,
      Path outputJar,
      String settingsJson,
      Consumer<String> logLine)
      throws IOException {
    run(inputJar, outputJar, settingsJson, logLine, "cli");
  }

  private static String uploadJob(
      VeylCredentials creds,
      Path inputJar,
      String settingsJson,
      String client,
      Consumer<String> logLine)
      throws IOException {
    String boundary = "veyl-" + UUID.randomUUID();
    byte[] jarBytes = Files.readAllBytes(inputJar);
    String filename = inputJar.getFileName().toString();
    String settings = settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson;

    HttpURLConnection conn =
        open(creds.apiUrl + "/api/jobs", "POST", creds.token, client, 10 * 60_000);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

    try (OutputStream out = conn.getOutputStream()) {
      writeField(out, boundary, "settings", settings);
      writeFile(out, boundary, "file", filename, "application/java-archive", jarBytes);
      out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    String body = readBody(conn);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new IOException(apiError("Upload failed", code, body));
    }
    Matcher m = JOB_ID.matcher(body);
    if (!m.find()) {
      throw new IOException(
          "Upload response missing job id: " + body.substring(0, Math.min(200, body.length())));
    }
    if (logLine != null) {
      logLine.accept("Veyl - server job " + m.group(1));
    }
    return m.group(1);
  }

  private static void downloadJob(
      VeylCredentials creds, String jobId, Path outputJar, String client) throws IOException {
    HttpURLConnection conn =
        open(creds.apiUrl + "/api/jobs/" + jobId + "/file", "GET", creds.token, client, 10 * 60_000);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new IOException(apiError("Download failed", code, readBody(conn)));
    }
    if (outputJar.getParent() != null) {
      Files.createDirectories(outputJar.getParent());
    }
    try (InputStream in = conn.getInputStream()) {
      Files.write(outputJar, in.readAllBytes());
    }
  }

  private static void writeField(OutputStream out, String boundary, String name, String value)
      throws IOException {
    // text/plain — avoid multipart JSON auto-parse turning the field into an object
    String head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.write(value.getBytes(StandardCharsets.UTF_8));
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static void writeFile(
      OutputStream out,
      String boundary,
      String name,
      String filename,
      String contentType,
      byte[] bytes)
      throws IOException {
    String head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"; filename=\""
            + filename.replace("\"", "")
            + "\"\r\nContent-Type: "
            + contentType
            + "\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.write(bytes);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
  }

  private static HttpURLConnection open(
      String url, String method, String token, String client, int readTimeoutMs) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(30_000);
    conn.setReadTimeout(readTimeoutMs);
    conn.setRequestProperty("Accept", "*/*");
    conn.setRequestProperty("User-Agent", "veyl-" + client + "/1.0");
    conn.setRequestProperty("X-Veyl-Client", client);
    conn.setRequestProperty("Authorization", "Bearer " + token);
    return conn;
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

  private static String apiError(String prefix, int code, String body) {
    String err = null;
    if (body != null) {
      Matcher m = ERROR.matcher(body);
      if (m.find()) {
        err = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
      }
    }
    if (err == null || err.isBlank()) {
      err = body == null || body.isBlank() ? ("HTTP " + code) : body.trim();
    }
    return prefix + " (HTTP " + code + "): " + err;
  }
}
