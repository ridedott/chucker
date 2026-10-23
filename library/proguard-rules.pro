-keep class com.chuckerteam.chucker.internal.data.entity.HttpTransaction { *; }
-keep class com.chuckerteam.chucker.internal.data.entity.GrpcEvent { *; }
-keep enum com.chuckerteam.chucker.internal.data.entity.GrpcEvent$* { *; }

# gRPC is an optional dependency, only needed by apps that use ChuckerGrpcInterceptor.
-dontwarn io.grpc.**

# Gson
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
