# Room, Hilt and kotlinx.serialization ship consumer rules.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Glance instantiates action callbacks through their public no-argument constructor.
-keep,allowoptimization class com.tarun.kairos.widgets.MarkDoneAction { public <init>(); }
