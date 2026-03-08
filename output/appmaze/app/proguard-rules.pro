-keep class com.massimiliano.appmaze.** { *; }
-keep interface com.massimiliano.appmaze.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.PrimaryKey <fields>;
    @androidx.room.ColumnInfo <fields>;
}

# Compose
-keep class androidx.compose.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
