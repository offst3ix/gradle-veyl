package net.st3ix.veyl.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.st3ix.veyl.VeylApi;
import net.st3ix.veyl.VeylAuth;
import net.st3ix.veyl.VeylAuthException;
import net.st3ix.veyl.VeylCredentials;
import net.st3ix.veyl.VeylInspect;
import net.st3ix.veyl.VeylKeepRules;
import net.st3ix.veyl.VeylObfuscate;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class VeylObfuscateTask extends DefaultTask {
  @Inject
  public VeylObfuscateTask() {
    getReplaceJar().convention(true);
    getRename().convention(true);
    getAesStrings().convention(false);
    getHiddenNumbers().convention(false);
    getHiddenCalls().convention(false);
    getControlFlow().convention(false);
    getAesMinLength().convention(3);
    getAesSkipClassNames().convention(true);
    getStyle().convention("letters");
    getDictionary().convention("lookalike");
    getSuggestKeeps().convention(true);
  }

  @InputFile
  public abstract RegularFileProperty getInputJar();

  @OutputFile
  public abstract RegularFileProperty getOutputJar();

  @Input
  public abstract Property<Boolean> getReplaceJar();

  @Input
  public abstract Property<Boolean> getRename();

  @Input
  public abstract Property<Boolean> getAesStrings();

  @Input
  public abstract Property<Boolean> getHiddenNumbers();

  @Input
  public abstract Property<Boolean> getHiddenCalls();

  @Input
  public abstract Property<Boolean> getControlFlow();

  @Input
  public abstract Property<Integer> getAesMinLength();

  @Input
  public abstract Property<Boolean> getAesSkipClassNames();

  @Input
  public abstract Property<String> getStyle();

  @Input
  public abstract Property<String> getDictionary();

  @Input
  @Optional
  public abstract ListProperty<String> getKeeps();

  @InputFile
  @Optional
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract RegularFileProperty getKeepFile();

  @Input
  public abstract Property<Boolean> getSuggestKeeps();

  /** Project dir used to auto-detect {@code veyl.rules}. */
  @Internal
  public abstract DirectoryProperty getProjectDirectory();

  @TaskAction
  public void obfuscate() {
    try {
      String user = VeylAuth.requireSignedIn("gradle");
      getLogger()
          .lifecycle(
              "Veyl: signed in as {} - uploading {} for server obfuscation",
              user,
              getInputJar().get().getAsFile().getName());

      List<String> keeps = resolveKeeps();
      getLogger()
          .lifecycle(
              "Veyl: options rename={} aes={} numbers={} calls={} flow={} - {} keep(s)",
              getRename().get(),
              getAesStrings().get(),
              getHiddenNumbers().get(),
              getHiddenCalls().get(),
              getControlFlow().get(),
              keeps.size());
      if (!keeps.isEmpty()) {
        for (String k : keeps) {
          getLogger().info("Veyl: keep {}", k);
        }
      }

      Path input = getInputJar().get().getAsFile().toPath();
      Path output = getOutputJar().get().getAsFile().toPath();

      if (Boolean.TRUE.equals(getSuggestKeeps().getOrElse(true))) {
        logKeepSuggestions(input, keeps);
      }

      Path workOut = output;
      if (getReplaceJar().get() && input.toAbsolutePath().equals(output.toAbsolutePath())) {
        workOut = Files.createTempFile("veyl-out-", ".jar");
      }
      String settings = settingsJson(keeps);
      try {
        VeylObfuscate.run(input, workOut, settings, line -> getLogger().lifecycle(line), "gradle");
      } catch (Exception e) {
        syncError(e.getMessage());
        throw e;
      }
      if (!workOut.equals(output)) {
        Files.move(workOut, output, StandardCopyOption.REPLACE_EXISTING);
      }
      getLogger().lifecycle("Veyl: wrote {}", output.toAbsolutePath());
    } catch (VeylAuthException e) {
      throw new GradleException(e.getMessage(), e);
    } catch (Exception e) {
      throw new GradleException(e.getMessage() == null ? "Veyl obfuscation failed" : e.getMessage(), e);
    }
  }

  private void syncError(String message) {
    try {
      var creds = VeylCredentials.load();
      if (creds.isEmpty()) return;
      String body =
          "{\"stacktrace\":\""
              + esc(message == null ? "obfuscate failed" : message)
              + "\",\"command\":\"gradle\",\"client\":\"gradle\"}";
      VeylApi.reportError(creds.get(), body, "gradle");
    } catch (Exception ignored) {
    }
  }

  private static String esc(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }

  private List<String> resolveKeeps() throws Exception {
    List<String> fromDsl = getKeeps().getOrElse(List.of());
    Path file = null;
    if (getKeepFile().isPresent()) {
      file = getKeepFile().get().getAsFile().toPath();
      if (!Files.isRegularFile(file)) {
        throw new GradleException("Veyl keepFile not found: " + file.toAbsolutePath());
      }
    } else if (getProjectDirectory().isPresent()) {
      file = VeylKeepRules.findDefault(getProjectDirectory().get().getAsFile().toPath());
    }
    List<String> fromFile = List.of();
    if (file != null) {
      fromFile = VeylKeepRules.load(file);
      getLogger().lifecycle("Veyl: loaded {} keep(s) from {}", fromFile.size(), file.getFileName());
    }
    return VeylKeepRules.merge(fromFile, fromDsl);
  }

  private void logKeepSuggestions(Path input, List<String> keeps) {
    try {
      VeylInspect.Report report = VeylInspect.inspect(input);
      List<VeylInspect.Suggestion> missing = VeylInspect.missing(report, keeps);
      if (missing.isEmpty()) {
        if (!report.suggestedKeeps.isEmpty()) {
          getLogger().lifecycle("Veyl: keep recommendations covered ({} detected)", report.suggestedKeeps.size());
        }
        return;
      }
      getLogger().lifecycle("Veyl: recommended keeps not yet configured ({}):", missing.size());
      for (VeylInspect.Suggestion s : missing) {
        getLogger()
            .lifecycle(
                "  -> {}{}",
                s.name,
                s.reason.isBlank() ? "" : "  (" + s.reason + ")");
      }
      getLogger()
          .lifecycle(
              "Veyl: tip - add them via keep(\"...\"), keepFile = file(\"veyl.rules\"), or: veyl recommend <jar> --out veyl.rules");
    } catch (Exception e) {
      getLogger().warn("Veyl: could not compute keep recommendations: {}", e.getMessage());
    }
  }

  private String settingsJson(List<String> keeps) {
    String keepJson =
        keeps.stream()
            .map(k -> "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(","));
    return "{"
        + "\"rename\":"
        + getRename().get()
        + ",\"aesStrings\":"
        + getAesStrings().get()
        + ",\"hiddenNumbers\":"
        + getHiddenNumbers().get()
        + ",\"hiddenCalls\":"
        + getHiddenCalls().get()
        + ",\"controlFlow\":"
        + getControlFlow().get()
        + ",\"aesMinLength\":"
        + getAesMinLength().get()
        + ",\"aesSkipClassNames\":"
        + getAesSkipClassNames().get()
        + ",\"style\":\""
        + getStyle().get()
        + "\",\"dictionary\":\""
        + getDictionary().get()
        + "\",\"keeps\":["
        + keepJson
        + "]}";
  }
}
