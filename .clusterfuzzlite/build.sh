#!/bin/bash -eu
# Builds the Jazzer fuzz targets in fuzz/ into $OUT, as ClusterFuzzLite
# expects: their jars, and one launcher per target. The image has no Android
# SDK, so Gradle configures only the projects :fuzz needs.

./gradlew --no-daemon --configure-on-demand --no-configuration-cache :fuzz:fuzzLibs

cp fuzz/build/fuzz-libs/*.jar "$OUT/"
classpath=$(cd fuzz/build/fuzz-libs && printf '$this_dir/%s:' *.jar)

for class in $(jar tf fuzz/build/fuzz-libs/fuzz.jar | grep -E 'Fuzzer\.class$'); do
  target=${class%.class}
  target=${target//\//.}
  name=${target##*.}
  # The LLVMFuzzerTestOneInput line marks the file as a fuzz target for ClusterFuzzLite.
  cat > "$OUT/$name" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
  --cp=$classpath --target_class=$target --jvm_args="\$mem_settings" \$@
EOF
  chmod +x "$OUT/$name"
done
