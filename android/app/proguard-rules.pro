# Правила для релизной сборки с R8.
# Библиотеки Room, Retrofit, OkHttp и MapLibre поставляют свои consumer-правила сами,
# здесь только то, что R8 не может вывести из кода.

# kotlinx.serialization: сериализаторы находятся рефлексией по имени класса.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.fogmap.** {
    *** Companion;
}
-keepclasseswithmembers class dev.fogmap.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.fogmap.**$$serializer { *; }

# DTO сетевого слоя: имена полей попадают в JSON, переименовывать их нельзя.
-keep class dev.fogmap.data.api.** { *; }

# SQLCipher: обращения идут из нативного кода, R8 их не видит.
-keep class net.zetetic.database.** { *; }

# Retrofit: сигнатуры generic-типов нужны для разбора ответов.
-keepattributes Signature, Exceptions
