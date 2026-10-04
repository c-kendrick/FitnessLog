plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("kotlin-parcelize") }
android {
    namespace = "au.com.kit.fitnesslogsync"
    compileSdk = 36
    defaultConfig {
        applicationId = "au.com.kit.fitnesslogsync"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
val samsungSdk = file("libs/samsung-health-data-api-1.1.0.aar")
check(samsungSdk.isFile) {
    "Samsung SDK missing. Download Samsung Health Data SDK v1.1.0 from developer.samsung.com/health/data/overview.html and copy samsung-health-data-api-1.1.0.aar into app/libs. See START_HERE.md."
}
dependencies {
    implementation(files(samsungSdk))
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
