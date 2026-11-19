# Google Cast: el framework carga OptionsProvider por reflexión.
-keep class com.screencastpro.connect.CastOptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider { *; }
