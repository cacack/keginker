# The ClusterFuzzLite build of the Jazzer fuzz targets in fuzz/; see
# docs/operations/ci.md. The base image brings Jazzer and a JDK.
FROM gcr.io/oss-fuzz-base/base-builder-jvm:v1@sha256:f78b70af260b975f3baa39cdd1d76a54305fb4c1600a630701e6f23b9478e355
COPY . $SRC/keginker
WORKDIR $SRC/keginker
COPY .clusterfuzzlite/build.sh $SRC/
