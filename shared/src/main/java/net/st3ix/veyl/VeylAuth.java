package net.st3ix.veyl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VeylAuth {
  private static final Pattern USERNAME = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");

  private VeylAuth() {}

  public static VeylCredentials login(String apiUrl, String username, String password) throws IOException {
    String base = trimSlash(apiUrl);
    if (base == null || base.isBlank()) {
      throw new VeylAuthException(
          VeylAuthException.Kind.VALIDATION, "Missing API URL. Use: veyl login");
    }
    if (username == null || username.isBlank()) {
      throw new VeylAuthException(VeylAuthException.Kind.VALIDATION, "Username is required.");
    }
    if (password == null || password.isEmpty()) {
      throw new VeylAuthException(VeylAuthException.Kind.VALIDATION, "Password is required.");
    }

    String body =
        "{\"username\":"
            + jsonString(username)
            + ",\"password\":"
            + jsonString(password)
            + ",\"name\":\"cli\"}";
    HttpURLConnection conn;
    try {
      conn = open(base + "/api/build/login", "POST", "cli");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json");
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      }
    } catch (UnknownHostException e) {
      throw network(base, "Unknown host - check the API URL.", e);
    } catch (ConnectException e) {
      throw network(base, "Could not connect - is the API running?", e);
    } catch (SocketTimeoutException e) {
      throw network(base, "Connection timed out.", e);
    } catch (IOException e) {
      throw network(base, e.getMessage() == null ? "Network error." : e.getMessage(), e);
    }

    String response = readBody(conn);
    int code = conn.getResponseCode();
    if (code == 200) {
      String token = field(response, "token");
      String user = nestedUsername(response);
      if (token == null || token.isBlank()) {
        throw new VeylAuthException(VeylAuthException.Kind.OTHER, "Login response missing token.");
      }
      return new VeylCredentials(base, token, user == null ? username : user);
    }
    throw mapLoginError(code, response, base);
  }

  /** Returns username when the build token is valid. */
  public static Optional<String> whoAmI(VeylCredentials credentials) throws IOException {
    return whoAmI(credentials, "cli");
  }

  public static Optional<String> whoAmI(VeylCredentials credentials, String client) throws IOException {
    HttpURLConnection conn;
    try {
      conn = open(credentials.apiUrl + "/api/build/me", "GET", client);
      conn.setRequestProperty("Authorization", "Bearer " + credentials.token);
    } catch (UnknownHostException e) {
      throw network(credentials.apiUrl, "Unknown host - check the API URL.", e);
    } catch (ConnectException e) {
      throw network(credentials.apiUrl, "Could not connect - is the API running?", e);
    } catch (SocketTimeoutException e) {
      throw network(credentials.apiUrl, "Connection timed out.", e);
    }
    String response = readBody(conn);
    int code = conn.getResponseCode();
    if (code == 401 || code == 403) return Optional.empty();
    if (code != 200) throw mapSessionError(code, response, credentials.apiUrl);
    String user = nestedUsername(response);
    return Optional.of(user == null ? credentials.username : user);
  }

  /** Validates login; returns the signed-in username. */
  public static String requireSignedIn() throws IOException {
    return requireSignedIn("cli");
  }

  public static String requireSignedIn(String client) throws IOException {
    Optional<VeylCredentials> loaded = VeylCredentials.load();
    if (loaded.isEmpty()) {
      throw new VeylAuthException(
          VeylAuthException.Kind.NOT_SIGNED_IN,
          "Not signed in to Veyl.\n"
              + "  -> Run:  veyl login\n"
              + "  -> Or set VEYL_TOKEN + VEYL_API_URL for CI");
    }
    VeylCredentials credentials = loaded.get();
    Optional<String> user;
    try {
      user = whoAmI(credentials, client);
    } catch (VeylAuthException e) {
      throw e;
    } catch (IOException e) {
      throw network(
          credentials.apiUrl,
          e.getMessage() == null ? "Could not verify session." : e.getMessage(),
          e);
    }
    if (user.isEmpty()) {
      throw new VeylAuthException(
          VeylAuthException.Kind.SESSION,
          "Veyl session expired or invalid (saved login for "
              + (credentials.username.isBlank() ? "unknown user" : credentials.username)
              + " @ "
              + credentials.apiUrl
              + ").\n"
              + "  -> Run:  veyl login");
    }
    return user.get();
  }

  public static void logout(VeylCredentials credentials) throws IOException {
    HttpURLConnection conn = open(credentials.apiUrl + "/api/build/logout", "POST", "cli");
    conn.setRequestProperty("Authorization", "Bearer " + credentials.token);
    conn.setDoOutput(true);
    conn.getOutputStream().close();
    readBody(conn);
  }

  private static VeylAuthException mapLoginError(int code, String body, String apiUrl) {
    String err = field(body, "error");
    String lower = err == null ? "" : err.toLowerCase();
    if (code == 401) {
      if (lower.contains("disabled")) {
        return new VeylAuthException(
            VeylAuthException.Kind.ACCOUNT_DISABLED,
            "Account disabled. Contact a Veyl admin.",
            code);
      }
      return new VeylAuthException(
          VeylAuthException.Kind.WRONG_CREDENTIALS,
          "Wrong username or password.\n"
              + "  -> Check spelling / caps\n"
              + "  -> API: "
              + apiUrl,
          code);
    }
    if (code == 403) {
      return new VeylAuthException(
          VeylAuthException.Kind.ACCOUNT_DISABLED,
          err != null && !err.isBlank() ? err : "Access denied (account may be disabled).",
          code);
    }
    if (code == 400) {
      return new VeylAuthException(
          VeylAuthException.Kind.VALIDATION,
          err != null && !err.isBlank()
              ? err
              : "Invalid login request (username 3–32 chars, password 10–128 chars).",
          code);
    }
    if (code == 404) {
      return new VeylAuthException(
          VeylAuthException.Kind.NETWORK,
          "Login endpoint not found (HTTP 404). Is this a Veyl API?\n  -> " + apiUrl + "/api/build/login",
          code);
    }
    if (code >= 500) {
      return new VeylAuthException(
          VeylAuthException.Kind.OTHER,
          "Veyl API error (HTTP " + code + ")"
              + (err != null && !err.isBlank() ? ": " + err : "")
              + "\n  -> " + apiUrl,
          code);
    }
    return new VeylAuthException(
        VeylAuthException.Kind.OTHER,
        err != null && !err.isBlank() ? err : ("Login failed (HTTP " + code + ")"),
        code);
  }

  private static VeylAuthException mapSessionError(int code, String body, String apiUrl) {
    String err = field(body, "error");
    return new VeylAuthException(
        VeylAuthException.Kind.OTHER,
        err != null && !err.isBlank()
            ? err + " (" + apiUrl + ")"
            : "Session check failed (HTTP " + code + ") @ " + apiUrl,
        code);
  }

  private static VeylAuthException network(String apiUrl, String detail, IOException cause) {
    VeylAuthException ex =
        new VeylAuthException(
            VeylAuthException.Kind.NETWORK,
            "Cannot reach Veyl API: " + detail + "\n  -> " + apiUrl);
    ex.initCause(cause);
    return ex;
  }

  private static HttpURLConnection open(String url, String method, String client) throws IOException {
    String kind = client == null || client.isBlank() ? "cli" : client.trim().toLowerCase();
    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(15_000);
    conn.setReadTimeout(30_000);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("User-Agent", "veyl-" + kind + "/1.0");
    conn.setRequestProperty("X-Veyl-Client", kind);
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

  private static String nestedUsername(String json) {
    Matcher m = USERNAME.matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String trimSlash(String url) {
    if (url == null) return "";
    String t = url.trim();
    while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
    return t;
  }
}
