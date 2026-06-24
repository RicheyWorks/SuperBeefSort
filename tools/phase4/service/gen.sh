#!/usr/bin/env sh
# Generate the Python gRPC stubs from the canonical proto into this directory.
# The proto is the single source of truth shared with the Java client build (src/main/proto).
set -e
PROTO_DIR="../../../src/main/proto"
python3 -m grpc_tools.protoc -I "$PROTO_DIR" \
  --python_out=. --grpc_python_out=. "$PROTO_DIR/sbs_intelligence.proto"
echo "generated sbs_intelligence_pb2.py + sbs_intelligence_pb2_grpc.py"
echo "deps: pip install grpcio grpcio-tools"
