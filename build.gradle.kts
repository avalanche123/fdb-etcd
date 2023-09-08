import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import java.net.URI

plugins {
  java
  application
  id("com.github.johnrengelman.shadow") version "7.1.2"
  id("com.google.protobuf") version "0.8.19"
}

group = "fr.pierrezemb"
version = "1.0.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven {
    url = URI.create("https://ossartifacts.jfrog.io/artifactory/fdb-record-layer/")
  }
}

val vertxVersion = "4.4.5"
val fdbJavaVersion = "6.2.19"
val fbdRecordLayerVersion = "2.10.164.0"
val junitJupiterVersion = "5.9.1"
val testContainersVersion = "1.19.0"

val mainVerticleName = "fr.pierrezemb.fdb.layer.etcd.MainVerticle"
val launcherClassName = "io.vertx.core.Launcher"

val watchForChange = "src/**/*"
val doOnChange = "${projectDir}/gradlew classes"

application {
  mainClass.set(launcherClassName)
}

dependencies {
  implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
  implementation("io.vertx:vertx-core")
  implementation("io.vertx:vertx-grpc")
  implementation("org.foundationdb:fdb-java:$fdbJavaVersion")
  implementation("org.foundationdb:fdb-record-layer-core-pb3:$fbdRecordLayerVersion")
  implementation("io.grpc:grpc-alts:1.57.2")
  implementation("javax.annotation:javax.annotation-api:1.3.2")

  testImplementation("io.vertx:vertx-junit5")
  testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
  testImplementation("org.testcontainers:testcontainers:$testContainersVersion")
  testImplementation("io.etcd:jetcd-core:0.7.6")
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<ShadowJar> {
  archiveClassifier.set("fat")
  manifest {
    attributes(mapOf("Main-Verticle" to mainVerticleName))
  }
  mergeServiceFiles()
}

tasks.withType<Test> {
  useJUnitPlatform()
  testLogging {
    events = setOf(PASSED, SKIPPED, FAILED)
  }
  testLogging {
    showStandardStreams = true
    exceptionFormat = FULL
  }
}

tasks.withType<JavaExec> {
  args = listOf("run", mainVerticleName, "--redeploy=$watchForChange", "--launcher-class=$launcherClassName", "--on-redeploy=$doOnChange")
}

protobuf {
  protoc {
    // The artifact spec for the Protobuf Compiler
    artifact = "com.google.protobuf:protoc:3.20.1"
  }
  plugins {
    id("grpc") {
      artifact = "io.grpc:protoc-gen-grpc-java:1.58.0"
    }
    id("vertx") {
      artifact = "io.vertx:vertx-grpc-protoc-plugin:$vertxVersion"
    }
  }
  generateProtoTasks {
    all().forEach {
      it.plugins {
        create("grpc")
        create("vertx")
      }
    }
  }
}
