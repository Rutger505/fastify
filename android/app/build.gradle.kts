import com.android.build.api.variant.ApplicationVariant
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val gitDescribe = providers.exec {
    commandLine("git", "describe", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "dev" } }

// Every commit on main is a new versionCode, so the in-app updater sees each published build as newer.
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }

// Where the in-app updater looks for version.json (android/scripts/publish). Debug builds only
// update when FASTIFY_UPDATE_URL is set, so emulator builds never pull the arm64 release.
val releaseUpdateUrl = "https://cdn.rutgerpronk.com/artifacts/fastify/"
val debugUpdateUrl = providers.environmentVariable("FASTIFY_UPDATE_URL").orElse("")

android {
    namespace = "com.rutger.fastify"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rutger.fastify"
        minSdk = 35
        targetSdk = 37
        versionCode = commitCount.get()
        versionName = "0.4.0+" + gitDescribe.get()
        ndk { abiFilters += (providers.environmentVariable("FASTIFY_ABIS").orNull ?: "arm64-v8a").split(" ") }
    }

    buildTypes {
        debug {
            buildConfigField("String", "UPDATE_URL", "\"${debugUpdateUrl.get()}\"")
        }
        release {
            buildConfigField("String", "UPDATE_URL", "\"$releaseUpdateUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded build: sign with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // lintVital on every release costs ~30 s; run ./gradlew lint by hand when needed.
    lint { checkReleaseBuilds = false }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/** Builds the Rust core with cargo-ndk and generates its UniFFI Kotlin bindings. */
abstract class CargoBuildTask : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val rustSources: ConfigurableFileCollection
    @get:Internal abstract val rustDir: DirectoryProperty
    @get:Input abstract val abis: Property<String>
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty
    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty

    @TaskAction
    fun build() {
        execOps.exec {
            workingDir = rustDir.get().asFile
            environment("FASTIFY_ABIS", abis.get())
            commandLine(
                "bash", "build-android.sh",
                jniLibsDir.get().asFile.absolutePath,
                kotlinDir.get().asFile.absolutePath,
            )
        }
    }
}

val rustRoot = rootProject.layout.projectDirectory.dir("../rust")

androidComponents {
    onVariants { variant: ApplicationVariant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val cargo = tasks.register<CargoBuildTask>("cargoBuild$name") {
            rustDir.set(rustRoot)
            rustSources.from(
                rustRoot.file("Cargo.toml"),
                rustRoot.file("Cargo.lock"),
                rustRoot.file("build-android.sh"),
                rustRoot.dir("core"),
                rustRoot.dir("bindgen"),
            )
            abis.set(providers.environmentVariable("FASTIFY_ABIS").orElse("arm64-v8a"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargo, CargoBuildTask::jniLibsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(cargo, CargoBuildTask::kotlinDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    debugImplementation(libs.androidx.ui.tooling)
}
