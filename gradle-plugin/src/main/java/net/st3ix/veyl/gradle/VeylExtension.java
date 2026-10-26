package net.st3ix.veyl.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class VeylExtension {
  public abstract Property<Boolean> getEnabled();

  public abstract Property<Boolean> getReplaceJar();

  public abstract Property<Boolean> getRename();

  public abstract Property<Boolean> getAesStrings();

  public abstract Property<Boolean> getHiddenNumbers();

  public abstract Property<Boolean> getHiddenCalls();

  public abstract Property<Boolean> getControlFlow();

  public abstract Property<Integer> getAesMinLength();

  public abstract Property<Boolean> getAesSkipClassNames();

  public abstract Property<String> getStyle();

  public abstract Property<String> getDictionary();

  public abstract ListProperty<String> getKeeps();

  /** Optional keep/rules file ({@code veyl.rules}). Auto-detected if unset. */
  public abstract RegularFileProperty getKeepFile();

  /** Log JAR inspect recommendations for missing keeps (default true). */
  public abstract Property<Boolean> getSuggestKeeps();

  public abstract Property<String> getOutputClassifier();

  public void keep(String name) {
    List<String> next = new ArrayList<>(getKeeps().getOrElse(List.of()));
    next.add(name);
    getKeeps().set(next);
  }
}
