# JNI-мост к libjni-c-toxcore.so (имена фиксированы в нативном коде).
-keep class com.zoffcc.applications.trifa.** { *; }

-dontwarn javax.script.ScriptEngineFactory
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.OpenSSLProvider
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Picasso тянет okhttp3, а тот ссылается на необязательные аннотации и TLS-провайдеры,
# которых нет на Android. Без этих правил R8 останавливает release-сборку
# ("Missing class javax.annotation.Nullable").
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------------------
# Защита от декомпиляции (release). Имена классов, методов и полей R8 заменяет на
# короткие бессмысленные, всё складывается в один пакет, строки шифрует StringFog.
# Номера строк оставляем (без имён файлов) - журнал ошибок остаётся полезным.
# ---------------------------------------------------------------------------
-repackageclasses 'o'
-allowaccessmodification
-renamesourcefileattribute S
-keepattributes LineNumberTable,SourceFile,Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

# Отладочные сообщения в release не нужны (предупреждения и ошибки остаются для журнала).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Нативный код находит методы и классы по именам - их не трогаем.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# WARP (AmneziaWG) - JNI-мост libwg-go.so.
-keep class org.amnezia.awg.** { *; }
# Ядро VPN (gomobile: libv2ray.aar) - классы вызываются из Go по именам.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
# Ядро Nova (nova-core-gn.aar, пакет gn вместо go) - тоже вызывается из Go по именам.
-keep class gn.** { *; }
-keep class nova.** { *; }
-dontwarn gn.**
-dontwarn nova.**

# Room: сущности и DAO (сгенерированный код обращается к ним по именам).
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ViewModels создаются фабрикой по классу.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }

# Компоненты из манифеста и ресурсов (активити, службы, приёмники, View из XML) - по именам.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.service.quicksettings.TileService
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Сериализация: Parcelable и Serializable.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
# Перечисления: values()/valueOf() используются при сохранении настроек.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Dagger: сгенерированные фабрики.
-dontwarn dagger.**
-keep class dagger.** { *; }

# Шифрование строк (StringFog) - класс расшифровки вызывается из всего кода.
-keep class com.github.megatronking.stringfog.** { *; }
-dontwarn com.github.megatronking.stringfog.**

# Код проверяет списки по имени класса (…RecyclerView, NavigationMenuView) - имена сохраняем.
-keepnames class androidx.recyclerview.widget.RecyclerView
-keepnames class com.google.android.material.internal.NavigationMenuView

# HTML-пузыри: мост для скриптов страницы (имена методов вызываются из JS).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
