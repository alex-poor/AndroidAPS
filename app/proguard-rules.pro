# =================================================================================================
# R8 keep rules.
#
# Upstream AndroidAPS has never enabled minification — `isMinifyEnabled true` does not appear
# anywhere in the project's history — so there are no inherited rules to build on. The file this
# replaces was the Android Studio template generated when the project was created in 2016; it still
# kept `android.support.v7` classes, a library AAPS migrated off years ago.
#
# OBFUSCATION IS DELIBERATELY OFF. AAPS resolves several things by class name at runtime, most
# importantly plugin fragments (PluginDescription.fragmentClass is a String handed to
# FragmentFactory.instantiate). Renaming breaks those in the worst way — at runtime, when a screen
# is opened, not at build time. Shrinking is where the size win is; renaming mostly compresses the
# string tables.
#
# If a screen crashes with ClassNotFoundException after a change here, the class was tree-shaken:
# add a -keep for it rather than turning minification back off.
#
# Escape hatch if R8 full mode turns out to be too aggressive:
#   android.enableR8.fullMode=false   in gradle.properties
# =================================================================================================

-dontobfuscate

# Readable stack traces in AndroidAPS.log — this app's support model is reading its own logs, and
# every user builds their own APK so there is no central mapping file to symbolicate against.
-keepattributes SourceFile,LineNumberTable,Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,AnnotationDefault

# -------------------------------------------------------------------------------------------------
# Fragments instantiated from a class-name string
#
# PluginDescription.fragmentClass is a String, resolved by TabPageAdapter, SingleFragmentActivity
# and the setup wizard's SWFragment. Nothing references these classes statically, so without this
# tree-shaking removes them and every plugin tab fails when opened.
# -------------------------------------------------------------------------------------------------
-keep public class * extends androidx.fragment.app.Fragment {
    public <init>(...);
}

# -------------------------------------------------------------------------------------------------
# Plugins
#
# Dagger constructs these so they are statically reachable, but their simpleName is passed as an
# intent extra (PLUGIN_NAME) to reopen a plugin's preference screen, and PluginDescription carries
# class references. Keep them whole.
# -------------------------------------------------------------------------------------------------
-keep class * extends app.aaps.core.interfaces.plugin.PluginBase { *; }

# -------------------------------------------------------------------------------------------------
# Automation triggers and actions
#
# ChooseTriggerDialog and ChooseActionDialog build their pickers with Class.forName(name).kotlin
# over a list of class names, then instantiate via the primary constructor.
# -------------------------------------------------------------------------------------------------
-keep class app.aaps.plugins.automation.triggers.** { *; }
-keep class app.aaps.plugins.automation.actions.** { *; }
-keep class app.aaps.plugins.automation.elements.** { *; }

# -------------------------------------------------------------------------------------------------
# Preference keys and other enums
#
# Preference keys are enum entries whose names are the SharedPreferences keys, and several enums are
# round-tripped through valueOf() when reading persisted state.
# -------------------------------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# -------------------------------------------------------------------------------------------------
# Persistence and wire formats
#
# Room and kotlinx.serialization ship their own consumer rules, so these are belt-and-braces for the
# reflective paths AAPS adds on top: entities are also serialised to Nightscout JSON by hand.
# -------------------------------------------------------------------------------------------------
-keep class app.aaps.database.entities.** { *; }
-keep class app.aaps.core.data.model.** { *; }
-keep class app.aaps.core.nssdk.localmodel.** { *; }
-keep class app.aaps.core.interfaces.rx.weardata.** { *; }

-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** serializer(...);
}

# -------------------------------------------------------------------------------------------------
# Kotlin runtime bits R8 cannot see through
# -------------------------------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# -------------------------------------------------------------------------------------------------
# The pump driver's native crypto (lazysodium/JNA) is reached through JNI by name.
# -------------------------------------------------------------------------------------------------
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
