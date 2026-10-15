# ONNX Runtime uses JNI; preserve its Java entry points.
-keep class ai.onnxruntime.** { *; }

-keep class com.google.ai.edge.litertlm.** { *; }
