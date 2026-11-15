plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "ru.it_spectrum.ai.rag.mcp"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.ai.mcp.server)
    implementation(libs.spring.ai.model)
    implementation(libs.spring.ai.vector.store)
    implementation(libs.spring.ai.pdf.document.reader)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)
    implementation(libs.lucene.highlighter)
    implementation(libs.onnxruntime)
    implementation(libs.djl.tokenizers)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    enabled = false
}

// The version the MCP server reports comes from gradle.properties, or -Pversion on a release.
tasks.processResources {
    val serverVersion = project.version.toString()
    inputs.property("serverVersion", serverVersion)
    filesMatching("application.yml") {
        filter { line: String -> line.replace("@version@", serverVersion) }
    }
}

tasks.bootJar {
    archiveBaseName.set("lucene-rag-server")
    archiveVersion.set("")
    archiveClassifier.set("")
    // ONNX Runtime and the tokenizer load native libraries; without this the JVM warns on stderr.
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
