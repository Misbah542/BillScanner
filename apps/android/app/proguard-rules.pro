# R8 configuration for release builds.
#
# This file replaces the prototype's, which was written for a Gson app and kept a
# `data.model` package that no longer exists. It had no kotlinx-serialization rules
# at all, which is the dangerous kind of wrong: the build succeeds, the APK
# installs, and then every API call fails at runtime because R8 stripped the
# generated serializers. R8 only runs in release, so debug testing never sees it.
#
# Check changes here with a release build, not a debug one:
#   ./gradlew assembleLiveRelease && ./gradlew installLiveRelease

# ---- crash reports ----
# Keep the line numbers so a stack trace from a minified build is readable, and
# rename the source file so class names are still obfuscated.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ----
# Every DTO is an @Serializable data class whose serializer is generated as a
# nested Companion or $$serializer. R8 cannot see it is used, because Retrofit
# looks it up reflectively through the converter, so without these rules it is
# removed and deserialisation throws SerializationException at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The serializable types themselves: the wire DTOs, and the SMS rule table that is
# parsed out of assets/shared/sms-rules.json.
-keep,includedescriptorclasses class com.snaptab.app.data.remote.dto.** { *; }
-keep,includedescriptorclasses class com.snaptab.app.data.sms.** { *; }

-dontnote kotlinx.serialization.**

# ---- Retrofit / OkHttp ----
# Retrofit 2.11 and OkHttp 4.12 both ship their own consumer rules, so nothing
# needs keeping by hand. These are the platform classes they reference reflectively
# on JVMs that are not Android, which R8 would otherwise warn about on every build.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Retrofit reads the generic return type of each suspend function, so the method
# signatures on the API interface have to survive.
-keepattributes Signature,Exceptions
-keep,allowobfuscation interface com.snaptab.app.data.remote.SnapTabApi { *; }

# ---- Room ----
# Room generates its implementations at compile time and ships consumer rules for
# them; the entities only need keeping because Room reflects over their fields.
-keep class com.snaptab.app.data.local.** { *; }

# ---- Hilt / Dagger ----
# Hilt ships consumer rules that cover the generated components. What it cannot know
# about is the WorkManager workers, which are constructed by name from a string.
-keep class com.snaptab.app.work.** { *; }

# ---- Compose ----
# The Compose compiler emits no reflective lookups, so nothing is needed. This is
# here to say so, rather than leaving the next person to wonder.

# ---- Credential Manager / Google sign-in ----
# Both artifacts ship consumer rules, so in principle nothing is needed here. The
# reason a rule is written anyway is the shape of the one thing that is reflective:
# CredentialManager picks its provider by loading
# androidx.credentials.playservices.CredentialProviderPlayServicesImpl *by name*, so
# R8 sees a class nobody calls. If the consumer rules ever stop covering it, the
# symptom is not a build failure — it is NoCredentialException on a phone that has a
# Google account, in release only, which is a long way to travel for a keep rule.
-keep class androidx.credentials.playservices.** { *; }

# GoogleIdTokenCredential.createFrom reads a Bundle by string key rather than by
# reflection, so the credential classes need no members kept. Their names are kept
# because the type is compared against TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, a string
# constant holding a class name.
-keepnames class com.google.android.libraries.identity.googleid.** { *; }
