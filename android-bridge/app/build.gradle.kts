plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.kaipay.notificationprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kaipay.notificationprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4-delivery-experimental"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxParallelForks = 1
            it.maxHeapSize = "512m"
            // Robolectric's documented Java 17+ reflection access, test JVM only.
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }
}
dependencies {
    implementation(project(":probe-core"))
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
