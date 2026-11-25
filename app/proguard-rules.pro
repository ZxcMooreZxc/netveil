-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class com.netveil.app.model.** { *; }
