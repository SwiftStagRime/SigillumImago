plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.swifstagrime.sigillumimago"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swifstagrime.sigillumimago"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    //Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    //Project modules
    implementation(project(":core_ui"))
    implementation(project(":core_common"))
    implementation(project(":core_data_api"))
    implementation(project(":core_data_impl"))
    implementation(project(":feature_auth"))
    implementation(project(":feature_camera"))
    implementation(project(":feature_gallery"))
    implementation(project(":feature_settings"))
    implementation(project(":feature_home"))
    implementation(project(":feature_recorder"))
    implementation(project(":feature_recordings"))
    implementation(project(":feature_doc_upload"))
    implementation(project(":feature_documents"))

    //Common
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    implementation(libs.androidx.lifecycle.process)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}