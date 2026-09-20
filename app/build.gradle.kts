import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "2.0.20"
}

// Diagnostic-log delivery credentials, read from local.properties (gitignored) at compile time so they
// never appear in the repo or any settings screen. Absent keys → "" and LogReporter then skips sending.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.enderthor.kpower"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enderthor.kpower"
        minSdk = 26   // Karoo 2 = Android 8 (API 26); lets FileLogTree's java.time work without desugaring
        // DELIBERADO, NO SUBIR sin sustituir antes la lectura de mapfiles. La superficie viva lee
        // /offline/maps/*.map por RUTA con mapsforge, y un .map no es un fichero de medios: desde
        // targetSdk 30 el almacenamiento con ambito es obligatorio y no se puede desactivar (en 29
        // todavia vale requestLegacyExternalStorage, que este manifiesto NO declara porque con 28
        // se da por hecho; si algun dia se sube a 29 hay que ponerlo a mano), asi
        // que READ_EXTERNAL_STORAGE, incluso CONCEDIDO, no abre esa ruta — solo el directorio
        // propio de la app y los medios que ella creo. Con 28 se aplica el almacenamiento heredado
        // y el permiso normal vale. Es lo que hace routegraph (tambien 28) con el mismo mapsforge,
        // la misma ruta y el mismo permiso; con 34 la feature estuvo MUERTA en silencio (la salida
        // del 2026-09-12: 1.138 de 1.138 clasificaciones a Unknown con spain.map presente).
        // Ojo: bajar el target es NECESARIO pero NO SUFICIENTE. El permiso clasico tiene que ser
        // WRITE_EXTERNAL_STORAGE, no READ, porque /offline se monta con gid=sdcard_rw y solo WRITE
        // concede ese gid; con targetSdk 28 y solo READ la feature tambien estuvo muerta (salida
        // del 2026-09-20). Ver el comentario de AndroidManifest.xml.
        // Alternativa si algun dia hay que subir: SAF, el ciclista elige la carpeta una vez y
        // MapFile se abre desde el FileChannel del descriptor, sin permiso ninguno.
        // KPower no usa notificaciones, PendingIntent, AlarmManager ni servicio en primer plano,
        // que es lo que suele cambiar al bajar el target.
        targetSdk = 28
        versionCode = 202609071
        versionName = "3.0.0"

        // Telegram bot for diagnostic-log delivery (only used when the rider enables diagnostic logging).
        // From local.properties: calib.bot_token / calib.chat_id. Absent → "" → LogReporter is a no-op.
        buildConfigField("String", "CALIB_BOT_TOKEN", "\"${localProps.getProperty("calib.bot_token", "")}\"")
        buildConfigField("String", "CALIB_CHAT_ID", "\"${localProps.getProperty("calib.chat_id", "")}\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    lint {
        // KPower se distribuye por sideload (Companion / GitHub Releases), NUNCA por Google Play,
        // asi que su fecha limite de targetSdk no aplica; sin esto lintVitalRelease tumba el build
        // de release. El target esta en 28 a proposito, ver el comentario en defaultConfig.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(files("libs/android_antlib_4-16-0.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.color)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.timber)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.foundation.layout.android)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.mapsforge.map.reader)
    testImplementation(libs.junit)
}

