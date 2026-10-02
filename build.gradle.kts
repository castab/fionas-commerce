import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.UUID

// fionas-commerce: the commerce backend of Fiona's Ice Cream, a concrete application built
// on commerce-runtime. It owns main(), its configuration, its logging backend, its
// migrations, and its deployable artifact. It is never published as a library.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.shadow)
}

group = "io.github.castab"

// The application's version is the Gradle project version: `version` in gradle.properties,
// a development default, overridden by a release build with -Pversion=<version>. The build
// writes it into fionas-commerce.properties, where the application (and the OpenAPI
// document's info.version) reads it.

// ---------------------------------------------------------------------------
// Java 25 is a hard requirement, as it is for commerce-runtime.
//
// Compilation, bytecode target, and test execution are all pinned to Java 25. If no Java
// 25 toolchain is installed, the build fails (auto-download is disabled in
// gradle.properties).
// ---------------------------------------------------------------------------
val requiredJavaVersion = 25

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJavaVersion)
    }
}

kotlin {
    jvmToolchain(requiredJavaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = requiredJavaVersion
}

dependencies {
    // The commerce runtime, and through it commerce-domain at the matching version, http4k,
    // kotlinx.serialization, JDBI, and HikariCP.
    implementation(libs.commerce.runtime)

    // http4k's contract routing and OpenAPI 3 renderer, from which /openapi.json and the
    // generated build/openapi document are both rendered, and Swagger UI served from its
    // WebJar (no CDN) at /docs. Same http4k version as commerce-runtime.
    implementation(libs.http4k.api.openapi)
    implementation(libs.http4k.api.ui.swagger)

    // http4k's Jackson, for one purpose only: commerce-runtime's offeringsOpenApiRenderer,
    // which describes the offerings routes' price union, renders schemas only through a
    // reflective format, and kotlinx.serialization is not one. Jackson never reads or writes
    // a request or response; CommerceJson (kotlinx.serialization) stays the wire format.
    implementation(libs.http4k.format.jackson)

    // The facade commerce-runtime logs through, for the application's own lifecycle logs.
    implementation(libs.kotlin.logging.jvm)

    // Fiona-owned human credentials. Argon2id hashes are encoded by the maintained JVM binding.
    implementation(libs.argon2.jvm)

    // The application's SLF4J provider, configured by src/main/resources/logback.xml.
    // commerce-runtime deliberately selects none.
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.logback.classic)
}

application {
    mainClass = "io.github.castab.fionas.commerce.MainKt"
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("fionas-commerce.properties") { expand("version" to version) }
}

// The deployable artifact: one executable jar holding the application, its dependencies,
// application.conf, logback.xml, and the Fiona migrations. build/libs/fionas-commerce-all.jar
tasks.shadowJar {
    archiveClassifier = "all"
    // The deployable path stays build/libs/fionas-commerce-all.jar whatever the version.
    archiveVersion = ""
    // Flyway discovers its PostgreSQL support through ServiceLoader files, which must be
    // merged rather than overwritten when dependencies are combined.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

// ---------------------------------------------------------------------------
// The OpenAPI document as a build artifact: build/openapi/fionas-commerce-openapi.json.
//
// Rendered by the `openapi` source set from the very contract the running application
// serves at /openapi.json (fionaApi), never from a hand-maintained file. Rendering calls no
// operation, so it needs no database, Docker, server, or network. The generator lives in
// its own source set so the deployable jar carries no second main().
// ---------------------------------------------------------------------------
val openapi by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[openapi.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[openapi.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

// The specs compare the generator's output with what the running application serves.
sourceSets.test {
    compileClasspath += openapi.output
    runtimeClasspath += openapi.output
}

val generateOpenApi by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generates build/openapi/fionas-commerce-openapi.json from the Fiona API contract."
    val document = layout.buildDirectory.file("openapi/fionas-commerce-openapi.json")
    classpath = openapi.runtimeClasspath
    mainClass = "io.github.castab.fionas.commerce.openapi.GenerateOpenApiKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(requiredJavaVersion) }
    outputs.file(document)
    argumentProviders.add(CommandLineArgumentProvider { listOf(document.get().asFile.absolutePath) })
}

tasks.assemble {
    dependsOn(generateOpenApi)
}

ktlint {
    outputToConsole = true
    coloredOutput = true
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.HTML)
    }
}

// As in commerce-runtime: compile main sources from the formatter's output, but when
// building, report style violations before compilation can format them away.
tasks.named("compileKotlin") {
    dependsOn("ktlintMainSourceSetFormat")
}
tasks.named("ktlintMainSourceSetFormat") {
    mustRunAfter("ktlintMainSourceSetCheck")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// ---------------------------------------------------------------------------
// PostgreSQL for tests, through the plain Docker CLI (the convention commerce-runtime's
// own tests follow).
//
// Database specs run against a real PostgreSQL with the real Flyway migrations, applied
// by commerce-runtime; there is no H2 and no separate test schema. The first test JVM
// that needs the database starts a throwaway container through `docker run`; Gradle
// removes it when the build ends, including when tests fail. Each spec creates, migrates,
// and drops its own database.
//
// When TEST_DATABASE_JDBC_URL is set (with TEST_DATABASE_USERNAME and
// TEST_DATABASE_PASSWORD), no container is started and that server is used instead. The
// user must be allowed to CREATE DATABASE.
// ---------------------------------------------------------------------------
abstract class PostgresTestDatabase :
    BuildService<PostgresTestDatabase.Parameters>,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val image: Property<String>
        val externalJdbcUrl: Property<String>
        val externalUsername: Property<String>
        val externalPassword: Property<String>
    }

    class Connection(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    private var containerName: String? = null

    val connection: Connection by lazy {
        if (parameters.externalJdbcUrl.isPresent) {
            Connection(
                parameters.externalJdbcUrl.get(),
                parameters.externalUsername.getOrElse("postgres"),
                parameters.externalPassword.getOrElse(""),
            )
        } else {
            startContainer()
        }
    }

    private fun startContainer(): Connection {
        val name = "fionas-commerce-test-postgres-${UUID.randomUUID().toString().take(8)}"
        val user = "fionas"
        val password = "fionas"
        docker(
            "run",
            "--detach",
            "--rm",
            "--name",
            name,
            "--label",
            "io.github.castab.fionas.commerce.test-database=true",
            "--env",
            "POSTGRES_USER=$user",
            "--env",
            "POSTGRES_PASSWORD=$password",
            "--env",
            "POSTGRES_DB=postgres",
            "--publish",
            "127.0.0.1::5432",
            parameters.image.get(),
        )
        containerName = name
        val port =
            docker("port", name, "5432/tcp")
                .lineSequence()
                .first()
                .substringAfterLast(':')
                .trim()
        // TCP readiness: the image's initdb phase serves only the Unix socket, so a
        // successful TCP check means the real server is accepting connections.
        val deadline = System.nanoTime() + 60_000_000_000L
        while (dockerExit("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", user, "-d", "postgres") != 0) {
            if (System.nanoTime() > deadline) throw GradleException("PostgreSQL test container $name did not become ready")
            Thread.sleep(250)
        }
        return Connection("jdbc:postgresql://127.0.0.1:$port/postgres", user, password)
    }

    override fun close() {
        containerName?.let { dockerExit("rm", "--force", it) }
    }

    private fun docker(vararg arguments: String): String {
        val process = ProcessBuilder("docker", *arguments).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (process.waitFor() != 0) {
            throw GradleException("`docker ${arguments.joinToString(" ")}` failed. Is Docker running?\n$output")
        }
        return output
    }

    private fun dockerExit(vararg arguments: String): Int {
        val process = ProcessBuilder("docker", *arguments).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor()
    }
}

/** Hands the database connection to the test JVM; resolved only when the tests really run. */
class TestDatabaseArguments(
    @get:Internal val database: Provider<PostgresTestDatabase>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val connection = database.get().connection
        return listOf(
            "-Dfionas.test.database.jdbcUrl=${connection.jdbcUrl}",
            "-Dfionas.test.database.username=${connection.username}",
            "-Dfionas.test.database.password=${connection.password}",
        )
    }
}

val postgresTestDatabase =
    gradle.sharedServices.registerIfAbsent("fionasPostgresTestDatabase", PostgresTestDatabase::class) {
        parameters.image = "postgres:18-alpine"
        parameters.externalJdbcUrl = providers.environmentVariable("TEST_DATABASE_JDBC_URL")
        parameters.externalUsername = providers.environmentVariable("TEST_DATABASE_USERNAME")
        parameters.externalPassword = providers.environmentVariable("TEST_DATABASE_PASSWORD")
    }

tasks.test {
    usesService(postgresTestDatabase)
    jvmArgumentProviders.add(TestDatabaseArguments(postgresTestDatabase))
}
