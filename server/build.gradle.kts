import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "com.businessstore"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"
val postgresBinariesVersion = "18.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Real PostgreSQL for tests without Docker: zonky downloads actual PG binaries.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:$postgresBinariesVersion"))
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:$postgresBinariesVersion")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:$postgresBinariesVersion")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // ProbeStateTest launches the REAL compiled ProbeState in a child JVM from
    // build/classes/java/probeState. That class is produced by
    // `compileProbeState`, not by `compileJava`/`compileTestKotlin`, so without
    // this dependency a clean tree either fails the test or (worse) runs a
    // stale class left over from an earlier build. Declared with the typed task
    // reference below so the ordering holds on a fresh checkout.
    dependsOn(tasks.named("compileProbeState"))
}

application {
    mainClass.set("com.businessstore.pos.ApplicationKt")
}

/**
 * ProbeState is a standalone Java tool. It lives in `server/tools/` for
 * traceability, outside the normal Java/Kotlin source sets, so it gets its own
 * source set and compile task with an unambiguous package and classpath.
 *
 * Must be declared at project scope: Gradle forbids registering source sets or
 * tasks from inside another task's configuration action.
 */
val probeStateSourceSet = sourceSets.create("probeState")
probeStateSourceSet.java.srcDir("tools")

val compileProbeState = tasks.register<JavaCompile>("compileProbeState") {
    group = "verification"
    description = "Compile the standalone read-only ProbeState audit tool."
    source(probeStateSourceSet.java.srcDirs)
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/probeState"))
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
    options.encoding = "UTF-8"
}

/**
 * Explicit schema provisioning. Normal startup never writes schema; this task
 * does, so it requires PROVISION_CONFIRM=yes and an explicit POS_DATABASE_URL.
 * There is no default target in the task or in the code it runs.
 */
tasks.register<JavaExec>("provisionDatabase") {
    group = "database"
    description = "Create the canonical POS schema on an explicitly confirmed, disposable database."
    mainClass.set("com.businessstore.pos.tools.ProvisionKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * Read-only staging identity probe. Runs ProbeState against a database nominated
 * by CHECK_URL with PROBE_CONFIRM=yes required. It never writes, never
 * provisions, and never applies migrations. Used by
 * .github/workflows/probe-staging-identity.yml.
 *
 * Credentials arrive through environment bindings only (CHECK_URL, CHECK_USER,
 * CHECK_PASS); none are passed as JVM system properties, so no password appears
 * in a process listing.
 */
tasks.register<JavaExec>("execProbeState") {
    group = "verification"
    description = "Run the read-only ProbeState audit against a nominated database (workflow use only)."
    mainClass.set("com.businessstore.pos.tools.ProbeState")

    dependsOn(compileProbeState)
    classpath = files(compileProbeState.map { it.outputs.files }) +
        sourceSets.main.get().runtimeClasspath

    environment("PROBE_CONFIRM", "yes")
}
