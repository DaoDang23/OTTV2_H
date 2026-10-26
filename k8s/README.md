# Worker autoscaling with KEDA

`match-worker-scaledobject.yaml` scales an existing StatefulSet named `ottv2-match-worker` from the ladder and tournament RabbitMQ queue lengths. Create a Kubernetes Secret named `ottv2-rabbitmq-credentials` with `host`, `username`, and `password` keys before applying it. The RabbitMQ URI should address the broker from the KEDA operator network.

The worker expects isolate boxes to be initialized on each worker node. Set `POD_NAME` from `metadata.name` and `ISOLATE_BOX_BASE_ID` to a node-specific non-overlapping base range; the StatefulSet ordinal reserves two consecutive box ids for the two bots. Set the match worker deployment's MinIO/RabbitMQ connection environment and mount the configured isolate box storage. Validate the sandbox/cgroup setup and image before enabling autoscaling.
