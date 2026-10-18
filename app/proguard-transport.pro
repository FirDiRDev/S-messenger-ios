# «Каналы связи»: встроенный Tor (библиотеки Briar Project).
# Управление Tor (jtorctl) и обёртка onionwrapper используют отражение и разбор ответов по именам.
-keep class org.briarproject.onionwrapper.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class org.briarproject.android.dontkillmelib.** { *; }
-dontwarn org.briarproject.**
-dontwarn net.freehaven.tor.control.**
-dontwarn javax.annotation.**
