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
    // The probe tools reference classes in the main source set (CheckSchema uses
    // com.businessstore.pos.tools.CheckSchemaConfig), so the main output must be
    // on the compile classpath, not only the runtime dependencies.
    dependsOn(tasks.named("classes"))
    classpath = files(sourceSets.main.get().output) + sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/probeState"))
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
    options.encoding = "UTF-8"
}

/**
 * Explicit schema provisioning â€” the ONLY task that writes schema.
 *
 * Normal server startup never writes schema: `Application.main` calls
 * `SchemaVerifier.requireProvisioned` (SELECTs only) and fails with an actionable
 * message when the baseline is absent. This task is the deliberate, separate
 * step that creates it.
 *
 * Everything is supplied by the environment; nothing has a default, and the
 * process refuses before opening a connection when anything is missing:
 *   PROVISION_CONFIRM=yes        exact confirmation, no other value proceeds
 *   POS_DATABASE_URL             jdbc:postgresql://<host>:<port>/<db>
 *   POS_DATABASE_USER            pooler administrator, postgres.<project-ref>
 *   POS_DATABASE_PASS            role password (optional; never logged)
 *   POS_EXPECTED_TARGET          20-char project ref the URL must resolve to
 *
 * The run applies the baseline and records version 0000 in ONE transaction, then
 * re-reads the baseline to verify it. Migrations 0001-0009 are NOT applied here;
 * they remain a separate step.
 */
tasks.register<JavaExec>("provisionDatabase") {
    group = "database"
    description = "Provision the baseline schema (version 0000) on an explicitly confirmed remote/Supabase target."
    // Entry point verified with `javap`: @JvmStatic main() inside `object Provision`
    // compiles to com.businessstore.pos.tools.Provision with a static main(String[]).
    // It does NOT generate ProvisionKt (that requires a top-level Kotlin function),
    // so this class name is the real JVM entry point, not a guess.
    mainClass.set("com.businessstore.pos.tools.Provision")
    // `classes` pulls in compileJava/compileKotlin AND processResources, so
    // /db/schema.sql is on the runtime classpath even from a clean checkout.
    // Depending on compileKotlin alone left processResources unrun.
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * Explicit LOCAL baseline provisioning â€” the developer-laptop counterpart to
 * `provisionDatabase`, kept strictly separate from it.
 *
 * The remote task requires a Supabase pooler login and a project reference, which
 * a laptop does not have; the local task requires loopback and an explicit local
 * confirmation, which a remote target can never satisfy. Both finish with the same
 * transactional Schema.apply and neither applies migrations 0001-0009.
 *
 *   LOCAL_PROVISION_CONFIRM=yes     exact confirmation
 *   POS_LOCAL_DATABASE_URL          jdbc:postgresql://localhost:5432/business_store
 *   POS_LOCAL_DATABASE_USER         the local role (e.g. business_store)
 *   POS_LOCAL_DATABASE_PASS         optional, never logged
 */
tasks.register<JavaExec>("provisionLocalDatabase") {
    group = "database"
    description = "Provision the baseline schema (version 0000) on an explicitly confirmed loopback database."
    // Verified with javap: @JvmStatic main() in `object ProvisionLocal` compiles
    // to the ProvisionLocal class, not ProvisionLocalKt.
    mainClass.set("com.businessstore.pos.tools.ProvisionLocal")
    dependsOn(tasks.named("classes"))
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

    // Staging values are required by ProbeState itself, which fails closed with
    // its own BLOCKED message. An unset value is passed through as blank so that
    // validation happens in the probe rather than here.
    environment(
        "POS_STAGING_PROJECT_REF" to
            (project.findProperty("POS_STAGING_PROJECT_REF") as String?
                ?: System.getenv("POS_STAGING_PROJECT_REF") ?: ""),
        "POS_STAGING_POOLER_HOST" to (System.getenv("POS_STAGING_POOLER_HOST") ?: ""),
        "POS_STAGING_DB_ROOT_CERT" to (System.getenv("POS_STAGING_DB_ROOT_CERT") ?: ""),
        "POS_STAGING_DB_ROOT_CERT_SHA256" to
            (System.getenv("POS_STAGING_DB_ROOT_CERT_SHA256") ?: ""),
        "PROBE_CONFIRM" to "yes",
    )
}
