import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `java-library`
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
val androidSdk = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")

dependencies {
    compileOnly(files("$androidSdk/platforms/android-36/android.jar"))
}

// The stubs are compile-time declarations only. Use the JDK that Gradle is
// already running with, while emitting Java 17-compatible
// class files for the Android module's Java 17 compile settings. This does not
// require a separate JDK 17 installation.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
