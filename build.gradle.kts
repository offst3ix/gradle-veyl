plugins {
  java
}

allprojects {
  group = "net.st3ix.veyl"
  version = "1.0.8"

  repositories {
    mavenCentral()
  }
}

subprojects {
  tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
  }
}
