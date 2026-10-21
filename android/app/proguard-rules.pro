# R8 rules for the release build, on top of proguard-android-optimize.txt and
# the consumer rules each dependency ships. Add a rule here only for something
# that is reached by name at runtime and that no library already covers.
#
# Covered by the libraries' own rules, so nothing here:
# - Room: room-runtime keeps `* extends RoomDatabase { <init>(); }`, which
#   is how ArdaasDatabase_Impl is found by name. Converters and the DAO are
#   called from generated code, not by reflection.
# - kotlinx.serialization: the core jar ships R8 rules for @Serializable
#   classes, and every decode here names its type at compile time.
# - Compose / AndroidX: shipped rules.
#
# Our own code (io.sarbloc.ardaas) uses no reflection, no getIdentifier, and
# loads its bundled JSON from assets/, which the resource shrinker never
# touches — bar the generated library list, R.raw.aboutlibraries, read by id
# and kept by res/raw/keep.xml. Saved instance state is plain strings, never
# class names.

# ONNX Runtime (com.microsoft.onnxruntime:onnxruntime-android) ships NO
# consumer rules (the AAR has no proguard.txt), yet libonnxruntime4j_jni.so
# reaches back into its Java classes by name: FindClass on OnnxTensor,
# OnnxMap, OnnxSequence, OnnxSparseTensor, OnnxValue, OrtException, TensorInfo,
# MapInfo, SequenceInfo, NodeInfo and OnnxModelMetadata, then GetMethodID /
# GetFieldID on their constructors and fields. R8 cannot see any of that, so
# it would rename or strip them and translation would fail only at runtime.
# The default file keeps native method names, but not the classes the native
# side constructs. Keep the whole package as ONNX Runtime's own docs advise:
# it is ~120 KB of classes, and a narrower list would have to be re-derived
# from the C sources on every ORT upgrade.
-keep class ai.onnxruntime.** { *; }
