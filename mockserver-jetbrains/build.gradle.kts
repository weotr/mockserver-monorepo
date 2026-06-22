plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.1.21"
}

group = "com.mock-server"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        // Bundled JSON plugin — provides the JsonSchemaProviderFactory API used to
        // associate the expectation schema with *.mockserver.json files.
        bundledPlugin("com.intellij.modules.json")
        // IntelliJ Platform test fixtures — provides BasePlatformTestCase, used by
        // MockServerSchemaWiringTest to assert, in a real headless IDE, that the
        // JSON-schema provider factory is wired up under the correct extension point.
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Gson for building/parsing the small JSON payloads exchanged with the running
    // MockServer (OpenAPI spec wrapping, pretty-printing). Declared explicitly so
    // MockServerRestClient is unit-testable on the plain test classpath without the
    // IntelliJ platform; the platform also bundles a compatible Gson at runtime.
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            // Open-ended compatibility: an empty untilBuild omits the `until-build`
            // upper bound from plugin.xml, so the plugin stays available in current
            // and future IDE builds (e.g. 261+) instead of being capped. The plugin
            // uses only stable, public platform APIs, so we don't pin an upper bound
            // pre-emptively — the Plugin Verifier (verifyPlugin) is the backstop that
            // catches any real incompatibility before release.
            untilBuild = provider { "" }
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
