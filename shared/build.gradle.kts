plugins {
  `java-library`
  `maven-publish`
}

java {
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "veyl-shared"
    }
  }
}
