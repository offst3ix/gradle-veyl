package net.st3ix.veyl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small HTTP helpers for build-token API calls (jobs, errors, version). */
public final class VeylApi {
  private VeylApi() {}

  public static Optional<String> latestCliVersion(String apiUrl) {
    try {
      HttpURLConnection conn = open(trimSlash(apiUrl) + "/api/build/version", "GET", null, "cli");
      String body = readBody(conn);
      if (conn.getResponseCode() != 200) return Optional.empty();
      String version = field(body, "cliVersion");
      return version == null || version.isBlank() ? Optional.empty() : Optional.of(version.trim());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static Optional<String> downloadUrl(String apiUrl) {
    try {
      HttpURLConnection conn = open(trimSlash(apiUrl) + "/api/build/version", "GET", null, "cli");
      String body = readBody(conn);
      if (conn.getResponseCode() != 200) return Optional.empty();
      String url = field(body, "downloadUrl");
      return url == null || url.isBlank() ? Optional.empty() : Optional.of(url.trim());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static void reportJob(VeylCredentials credentials, String jsonBody, String client)
      throws IOException {
    postJson(credentials, "/api/build/jobs", jsonBody, client);
  }

  public static void reportError(VeylCredentials credentials, String jsonBody, String client)
      throws IOException {
    postJson(credentials, "/api/build/errors", jsonBody, client);
  }

  private static void postJson(
      VeylCredentials credentials, String path, String jsonBody, String client) throws IOException {
    HttpURLConnection conn =
        open(credentials.apiUrl + path, "POST", credentials.token, client == null ? "cli" : client);
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/json");
    try (OutputStream out = conn.getOutputStream()) {
      out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
    }
    readBody(conn);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new IOException("API " + path + " failed (HTTP " + code + ")");
    }
  }

  private static HttpURLConnection open(String url, String method, String token, String client)
      throws IOException {
    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(20_000);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "veyl-" + client + "/1.0");
    conn.setRequestProperty("X-Veyl-Client", client);
    if (token != null && !token.isBlank()) {
      conn.setRequestProperty("Authorization", "Bearer " + token);
    }
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

  private static String field(String json, String key) {
    if (json == null) return null;
    Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static String trimSlash(String url) {
    if (url == null) return "";
    String t = url.trim();
    while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
    return t;
  }
}
