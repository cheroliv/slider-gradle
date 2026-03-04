import java.io.File.separator

plugins { `kotlin-dsl` }

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    listOf(
        "https://repo.gradle.org/gradle/libs-releases/",
        "https://plugins.gradle.org/m2/",
        "https://maven.xillio.com/artifactory/libs-release/",
        "https://mvnrepository.com/repos/springio-plugins-release",
        "https://archiva-repository.apache.org/archiva/repository/public/"
    ).forEach(::maven)
}


val asciidoctorGradleVersionKey = "asciidoctor-gradle.version"
val jacksonVersionKey = "jackson.version"
val arrowKtVersionKey = "arrow-kt.version"
val jgitVersionKey = "jgit.version"
val langchain4jVersionKey = "langchain4j.version"
val langchain4jExtensionVersionKey = "langchain4j-extension.version"

val Project.versions: Map<String, String>
    get() = mapOf(
        asciidoctorGradleVersionKey to project.properties[asciidoctorGradleVersionKey].toString(),
        jacksonVersionKey to project.properties[jacksonVersionKey].toString(),
        arrowKtVersionKey to project.properties[arrowKtVersionKey].toString(),
        jgitVersionKey to project.properties[jgitVersionKey].toString(),
        langchain4jVersionKey to project.properties[langchain4jVersionKey].toString(),
        langchain4jExtensionVersionKey to project.properties[langchain4jExtensionVersionKey].toString()
    )

dependencies {
    setOf(
        "com.avast.gradle:gradle-docker-compose-plugin:0.17.6",
        "com.github.node-gradle:gradle-node-plugin:7.0.1",
        "jakarta.xml.bind:jakarta.xml.bind-api:4.0.2",
        "com.fasterxml.jackson.module:jackson-module-kotlin:${versions[jacksonVersionKey]}",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${versions[jacksonVersionKey]}",
        "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${versions[jacksonVersionKey]}",
        "com.fasterxml.jackson.module:jackson-module-jsonSchema:${versions[jacksonVersionKey]}",
        "org.eclipse.jgit:org.eclipse.jgit:${versions[jgitVersionKey]}",
        "org.eclipse.jgit:org.eclipse.jgit.archive:${versions[jgitVersionKey]}",
        "org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:${versions[jgitVersionKey]}",
        "io.arrow-kt:arrow-core:${versions[arrowKtVersionKey]}",
        "io.arrow-kt:arrow-fx-coroutines:${versions[arrowKtVersionKey]}",
        "io.arrow-kt:arrow-integrations-jackson-module:0.14.1",
        "org.apache.poi:poi-ooxml:5.2.5",
        "org.jbake:jbake-gradle-plugin:5.5.0",
        "org.slf4j:slf4j-simple:2.0.16",
        "org.asciidoctor:asciidoctorj-diagram:2.3.1",
        "org.asciidoctor:asciidoctor-gradle-jvm-slides:${versions[asciidoctorGradleVersionKey]}",
        "org.asciidoctor:asciidoctor-gradle-base:${versions[asciidoctorGradleVersionKey]}",
        "org.asciidoctor:asciidoctor-gradle-jvm-gems:${versions[asciidoctorGradleVersionKey]}",
        "com.burgstaller:okhttp-digest:1.10",
        "org.ysb33r.gradle:grolifant:0.12.1",
        "com.avast.gradle:gradle-docker-compose-plugin:0.14.2",
        "org.gradle:gradle-tooling-api:8.6",
        "dev.langchain4j:langchain4j:${versions[langchain4jVersionKey]}",
        "dev.langchain4j:langchain4j-ollama:${versions[langchain4jExtensionVersionKey]}",
        "dev.langchain4j:langchain4j-hugging-face:${versions[langchain4jExtensionVersionKey]}",
        "dev.langchain4j:langchain4j-google-ai-gemini:${versions[langchain4jExtensionVersionKey]}",
        "dev.langchain4j:langchain4j-mistral-ai:${versions[langchain4jExtensionVersionKey]}",
        "dev.langchain4j:langchain4j-pgvector:${versions[langchain4jExtensionVersionKey]}",
//        "com.google.apis:google-api-services-forms:v1-rev20220908-2.0.0",
//        "com.google.apis:google-api-services-drive:v3-rev197-1.25.0",
//        "com.google.api-client:google-api-client-jackson2:2.3.0",
//        "com.google.auth:google-auth-library-oauth2-http:1.23.0",
//        "org.jetbrains.kotlin:kotlin-stdlib",
//        "commons-io:commons-io:$commonsIoVersion",
//        "org.tukaani:xz:1.9",
//        "org.testcontainers:testcontainers:$testcontainersVersion",
//        "org.testcontainers:ollama:$testcontainersVersion",
    ).forEach(::implementation)
    runtimeOnly("com.sun.xml.bind:jaxb-impl:4.0.5")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val functionalTestSourceSet: SourceSet = sourceSets.create("functionalTest")

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

tasks.named<Task>("check") { dependsOn(functionalTest) }

tasks.named<Test>("test") { useJUnitPlatform() }

tasks.withType<JavaExec> {
    jvmArgs = setOf(
        "--add-modules=jdk.incubator.vector",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview"
    ).toList()
}

tasks.register<Exec>("reportTests") {
    group = "verification"
    description = "Check buildSrc project classes then show report in firefox"
    dependsOn("check")
    commandLine(
        "firefox",
        "--new-tab",
        "build${separator}reports${separator}tests${separator}test${separator}index.html"
            .run(layout.projectDirectory.asFile.toPath()::resolve)
            .toAbsolutePath(),
    )
}

tasks.register<Exec>("reportFunctionalTests") {
    group = "verification"
    description = "Functionally check buildSrc project classes then show report in firefox"
    dependsOn("check")
    commandLine(
        "firefox",
        "--new-tab",
        "build${separator}reports${separator}tests${separator}functionalTest${separator}index.html"
            .run(layout.projectDirectory.asFile.toPath()::resolve)
            .toAbsolutePath(),
    )
}
