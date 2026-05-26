plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "dev.glass.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.glass.phone"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val transportKind = (project.findProperty("transportKind") as String?) ?: "tcp"
        buildConfigField("String", "TRANSPORT_KIND", "\"" + transportKind + "\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "TRANSPORT_KIND", "\"tcp\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "TRANSPORT_KIND", "\"rfcomm\"")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":osmand-api"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
