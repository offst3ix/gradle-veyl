package net.st3ix.veyl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stored under {@code ~/.veyl/credentials.json}. */
public final class VeylCredentials {
  private static final Pattern API = Pattern.compile("\"apiUrl\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern USER = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");

  public final String apiUrl;
  public final String token;
  public final String username;

  public VeylCredentials(String apiUrl, String token, String username) {
    this.apiUrl = trimSlash(apiUrl);
    this.token = token;
    this.username = username == null ? "" : username;
  }

  public static Path credentialsPath() {
    String override = System.getenv("VEYL_CREDENTIALS");
    if (override != null && !override.isBlank()) return Path.of(override);
    return Path.of(System.getProperty("user.home"), ".veyl", "credentials.json");
  }

  public static Optional<VeylCredentials> load() {
    String envToken = System.getenv("VEYL_TOKEN");
    String envApi = System.getenv("VEYL_API_URL");
    if (envToken != null && !envToken.isBlank()) {
      String api = envApi != null && !envApi.isBlank() ? envApi : "https://api.st3ix.net";
      return Optional.of(new VeylCredentials(api, envToken.trim(), System.getenv().getOrDefault("VEYL_USER", "")));
    }
    Path path = credentialsPath();
    if (!Files.isRegularFile(path)) return Optional.empty();
    try {
      String json = Files.readString(path, StandardCharsets.UTF_8);
      String api = first(API, json);
      String token = first(TOKEN, json);
      String user = first(USER, json);
      if (api == null || token == null || api.isBlank() || token.isBlank()) return Optional.empty();
      return Optional.of(new VeylCredentials(api, token, user == null ? "" : user));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  public void save() throws IOException {
    Path path = credentialsPath();
    Files.createDirectories(path.getParent());
    String json =
        "{\n"
            + "  \"apiUrl\": "
            + quote(apiUrl)
            + ",\n"
            + "  \"token\": "
            + quote(token)
            + ",\n"
            + "  \"username\": "
            + quote(username)
            + "\n"
            + "}\n";
    Files.writeString(path, json, StandardCharsets.UTF_8);
  }

  public static void clear() throws IOException {
    Path path = credentialsPath();
    Files.deleteIfExists(path);
  }

  private static String first(Pattern pattern, String json) {
    Matcher m = pattern.matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String trimSlash(String url) {
    if (url == null) return "";
    String t = url.trim();
    while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
    return t;
  }
}
