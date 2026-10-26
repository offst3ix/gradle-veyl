package net.st3ix.veyl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keep / rules file parser.
 *
 * <p>Format ({@code veyl.rules} or {@code veyl-keeps.txt}):
 *
 * <pre>
 * # comments
 * me.example.Main
 * me.example.**
 * keep me.example.api.Service
 * -keep class me.example.Main
 * </pre>
 */
public final class VeylKeepRules {
  private VeylKeepRules() {}

  /** Default filenames looked up in the project directory. */
  public static final List<String> DEFAULT_NAMES = List.of("veyl.rules", "veyl-keeps.txt", ".veylkeep");

  public static Path findDefault(Path projectDir) {
    if (projectDir == null || !Files.isDirectory(projectDir)) return null;
    for (String name : DEFAULT_NAMES) {
      Path candidate = projectDir.resolve(name);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    return null;
  }

  public static List<String> load(Path file) throws IOException {
    if (file == null || !Files.isRegularFile(file)) return List.of();
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    LinkedHashSet<String> keeps = new LinkedHashSet<String>();
    for (String raw : lines) {
      String rule = parseLine(raw);
      if (rule != null) keeps.add(rule);
    }
    return new ArrayList<String>(keeps);
  }

  public static List<String> merge(List<String> fromFile, List<String> fromDsl) {
    LinkedHashSet<String> merged = new LinkedHashSet<String>();
    if (fromFile != null) {
      for (String k : fromFile) {
        if (k != null && !k.isBlank()) merged.add(k.trim());
      }
    }
    if (fromDsl != null) {
      for (String k : fromDsl) {
        if (k != null && !k.isBlank()) merged.add(k.trim());
      }
    }
    return new ArrayList<String>(merged);
  }

  /** True if {@code candidate} is already covered by an exact keep or a package prefix keep. */
  public static boolean covers(Set<String> keeps, String candidate) {
    if (candidate == null || candidate.isBlank() || keeps == null || keeps.isEmpty()) return false;
    String name = candidate.trim();
    if (keeps.contains(name)) return true;
    String slash = name.replace('.', '/');
    for (String keep : keeps) {
      if (keep == null || keep.isBlank()) continue;
      String k = keep.trim();
      if (k.equals(name)) return true;
      if (k.endsWith(".**")) {
        String prefix = k.substring(0, k.length() - 3);
        if (name.equals(prefix) || name.startsWith(prefix + ".")) return true;
      } else if (k.endsWith(".*")) {
        String prefix = k.substring(0, k.length() - 2);
        if (name.equals(prefix)) return true;
        if (name.startsWith(prefix + ".") && !name.substring(prefix.length() + 1).contains(".")) {
          return true;
        }
      } else if (k.endsWith("/")) {
        if (slash.startsWith(k) || slash.equals(k.substring(0, k.length() - 1))) return true;
      }
    }
    return false;
  }

  static String parseLine(String raw) {
    if (raw == null) return null;
    String line = raw.trim();
    if (line.isEmpty()) return null;
    if (line.startsWith("#") || line.startsWith("//")) return null;
    int hash = line.indexOf(" #");
    if (hash > 0) line = line.substring(0, hash).trim();
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.startsWith("-keep")) {
      line = stripKeepProguard(line);
    } else if (lower.startsWith("keep ")) {
      line = line.substring(5).trim();
    } else if (lower.startsWith("keep\t")) {
      line = line.substring(5).trim();
    }
    if (line.isEmpty() || line.startsWith("-")) return null;
    return line;
  }

  private static String stripKeepProguard(String line) {
    String s = line.trim();
    // -keep class com.foo.Bar { *; }  /  -keep class com.foo.**
    int classAt = s.toLowerCase(Locale.ROOT).indexOf(" class ");
    if (classAt >= 0) {
      s = s.substring(classAt + " class ".length()).trim();
    } else if (s.toLowerCase(Locale.ROOT).startsWith("-keep")) {
      s = s.substring(5).trim();
    }
    int brace = s.indexOf('{');
    if (brace >= 0) s = s.substring(0, brace).trim();
    return s;
  }
}
