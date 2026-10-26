package net.st3ix.veyl.gradle;

import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

public class VeylPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    project.getPluginManager().apply("java");

    VeylExtension ext = project.getExtensions().create("veyl", VeylExtension.class);
    ext.getEnabled().convention(true);
    ext.getReplaceJar().convention(true);
    ext.getRename().convention(true);
    ext.getAesStrings().convention(false);
    ext.getHiddenNumbers().convention(false);
    ext.getHiddenCalls().convention(false);
    ext.getControlFlow().convention(false);
    ext.getAesMinLength().convention(3);
    ext.getAesSkipClassNames().convention(true);
    ext.getStyle().convention("letters");
    ext.getDictionary().convention("lookalike");
    ext.getKeeps().convention(List.of());
    ext.getSuggestKeeps().convention(true);
    ext.getOutputClassifier().convention("obfuscated");

    project.afterEvaluate(
        p -> {
          if (!Boolean.TRUE.equals(ext.getEnabled().getOrNull())) return;
          wire(p, ext, "jar");
          if (p.getTasks().findByName("shadowJar") != null) {
            wire(p, ext, "shadowJar");
          }
        });
  }

  private void wire(Project project, VeylExtension ext, String archiveTaskName) {
    var archive = project.getTasks().named(archiveTaskName, AbstractArchiveTask.class);
    String taskName = "veylObfuscate" + capitalize(archiveTaskName);
    TaskProvider<VeylObfuscateTask> obfuscate =
        project
            .getTasks()
            .register(
                taskName,
                VeylObfuscateTask.class,
                task -> {
                  task.setGroup("veyl");
                  task.setDescription(
                      "Upload " + archiveTaskName + " to Veyl for server-side obfuscation");
                  task.dependsOn(archive);
                  task.getInputJar().set(archive.flatMap(AbstractArchiveTask::getArchiveFile));
                  if (Boolean.TRUE.equals(ext.getReplaceJar().getOrElse(true))) {
                    task.getOutputJar().set(archive.flatMap(AbstractArchiveTask::getArchiveFile));
                  } else {
                    task.getOutputJar()
                        .fileProvider(
                            project.provider(
                                () -> {
                                  AbstractArchiveTask jarTask = archive.get();
                                  String base = jarTask.getArchiveBaseName().getOrElse("app");
                                  String ver = jarTask.getArchiveVersion().getOrElse("");
                                  String classifier = ext.getOutputClassifier().getOrElse("obfuscated");
                                  String name =
                                      base
                                          + (ver.isEmpty() ? "" : "-" + ver)
                                          + "-"
                                          + classifier
                                          + ".jar";
                                  return project
                                      .getLayout()
                                      .getBuildDirectory()
                                      .file("libs/" + name)
                                      .get()
                                      .getAsFile();
                                }));
                  }
                  task.getReplaceJar().set(ext.getReplaceJar());
                  task.getRename().set(ext.getRename());
                  task.getAesStrings().set(ext.getAesStrings());
                  task.getHiddenNumbers().set(ext.getHiddenNumbers());
                  task.getHiddenCalls().set(ext.getHiddenCalls());
                  task.getControlFlow().set(ext.getControlFlow());
                  task.getAesMinLength().set(ext.getAesMinLength());
                  task.getAesSkipClassNames().set(ext.getAesSkipClassNames());
                  task.getStyle().set(ext.getStyle());
                  task.getDictionary().set(ext.getDictionary());
                  task.getKeeps().set(ext.getKeeps());
                  task.getSuggestKeeps().set(ext.getSuggestKeeps());
                  task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
                  if (ext.getKeepFile().isPresent()) {
                    task.getKeepFile().set(ext.getKeepFile());
                  }
                });

    archive.configure(t -> t.finalizedBy(obfuscate));
    if (project.getTasks().findByName("build") != null) {
      project.getTasks().named("build").configure(t -> t.dependsOn(obfuscate));
    }
  }

  private static String capitalize(String value) {
    if (value == null || value.isEmpty()) return value;
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
