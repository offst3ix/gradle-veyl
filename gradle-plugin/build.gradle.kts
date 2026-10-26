plugins {
  `java-gradle-plugin`
  `maven-publish`
  id("com.gradle.plugin-publish") version "1.3.1"
}

val sharedProject = project(":shared")
dependencies {
  compileOnly(sharedProject)
}

tasks.named<Jar>("jar") {
  dependsOn(sharedProject.tasks.named("classes"))
  from(sharedProject.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java).named("main").map { it.output })
}

gradlePlugin {
  website = "https://veyl.sh"
  vcsUrl = "https://github.com/offst3ix/gradle-veyl"
  plugins {
    create("veyl") {
      id = "net.st3ix.veyl"
      implementationClass = "net.st3ix.veyl.gradle.VeylPlugin"
      displayName = "Veyl"
      description =
        "Upload your project JAR to Veyl for server-side Java bytecode obfuscation (requires veyl login)."
      tags.set(listOf("obfuscation", "java", "minecraft", "security", "bytecode"))
    }
  }
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "veyl-gradle"
    }
  }
}
