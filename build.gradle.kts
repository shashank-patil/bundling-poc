plugins {
  kotlin("jvm") version "1.9.24"
  application
}

repositories { mavenCentral() }

val ktorVersion = "2.3.12"

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

  implementation("org.postgresql:postgresql:42.7.3")
  implementation("com.zaxxer:HikariCP:5.1.0")
  implementation("org.flywaydb:flyway-core:10.15.0")
  implementation("org.flywaydb:flyway-database-postgresql:10.15.0")

  implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")

  implementation("ch.qos.logback:logback-classic:1.5.6")

  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }

application {
  mainClass.set("in.porter.archival.app.MainKt")
  // The LLD pins the job to a 512 MB heap; the PoC runs under the same ceiling
  // so that streaming a multi-GiB bundle is actually proven, not assumed.
  applicationDefaultJvmArgs = listOf("-Xmx512m")
}

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") { standardInput = System.`in` }
