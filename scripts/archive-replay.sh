#!/usr/bin/env bash
# Read an hour range of the archive back out of S3, as a Kubernetes Job in the cluster.
#
#   ./scripts/archive-replay.sh <topic> <from> [to] [target-topic]
#
#   ./scripts/archive-replay.sh position.events.v1 2026-09-11T06:00:00Z
#       verify one hour: every line parses, sits under the hour it claims, and has an event id
#   ./scripts/archive-replay.sh exceptions.v1 2026-09-11T00:00:00Z 2026-09-12T00:00:00Z
#       verify a whole UTC day
#   ./scripts/archive-replay.sh shipment.derived.v1 2026-09-11T06:00:00Z "" shipment.derived.v1
#       republish an hour onto the topic it came from (for example into a recreated cluster)
#
# Hours are UTC, and are the hours Kafka *received* the events, not the hours they describe.
#
# Runs as the archive-replay service account, whose role may list and read the archive and nothing
# else, using the same image the archiver is running -- so the reader is always the build that
# matches the writer. The Job's exit code is the verdict, and its log is the report.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."

TOPIC="${1:-}"
FROM="${2:-}"
TO="${3:-}"
TARGET="${4:-}"
[ -n "$TOPIC" ] && [ -n "$FROM" ] || die "Usage: $(basename "$0") <topic> <from> [to] [target-topic]"

kubectl get configmap/archive-destination -n fleet >/dev/null 2>&1 \
  || die "No archive-destination ConfigMap. Run ./scripts/aws-link.sh"
image="$(kubectl get deployment/archiver -n fleet -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)" \
  || die "No archiver deployment to take the image from."
pull="$(kubectl get deployment/archiver -n fleet -o jsonpath='{.spec.template.spec.containers[0].imagePullPolicy}')"

name="archive-replay-$(date -u +%Y%m%d%H%M%S)"
args="            - --fleet.archiver.mode=replay
            - --spring.main.web-application-type=none
            - --fleet.archiver.replay.topic=$TOPIC
            - --fleet.archiver.replay.from=$FROM"
[ -n "$TO" ] && args="$args
            - --fleet.archiver.replay.to=$TO"
[ -n "$TARGET" ] && args="$args
            - --fleet.archiver.replay.target=$TARGET"

log "Starting job/$name ($TOPIC from $FROM${TO:+ to $TO}${TARGET:+, republishing to $TARGET})"
kubectl apply -f - >/dev/null <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: $name
  namespace: fleet
  labels:
    app.kubernetes.io/name: archive-replay
    app.kubernetes.io/part-of: fleet-tracking
spec:
  # A verdict, not a service: a failed verification must be reported, not retried into a success.
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        app.kubernetes.io/name: archive-replay
    spec:
      restartPolicy: Never
      serviceAccountName: archive-replay
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
      containers:
        - name: replay
          image: $image
          imagePullPolicy: $pull
          args:
$args
          envFrom:
            - configMapRef:
                name: platform-endpoints
          env:
            - name: FLEET_ARCHIVER_BUCKET
              valueFrom: {configMapKeyRef: {name: archive-destination, key: bucket}}
            - name: FLEET_ARCHIVER_REGION
              valueFrom: {configMapKeyRef: {name: archive-destination, key: region}}
            - name: AWS_REGION
              valueFrom: {configMapKeyRef: {name: archive-destination, key: region}}
            - name: AWS_ROLE_ARN
              valueFrom: {configMapKeyRef: {name: archive-destination, key: replay-role-arn}}
            - name: AWS_WEB_IDENTITY_TOKEN_FILE
              value: /var/run/secrets/aws/token
            - name: AWS_ROLE_SESSION_NAME
              value: archive-replay
          resources:
            requests: {memory: 320Mi, cpu: 100m}
            limits: {memory: 768Mi}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
            - {name: aws-token, mountPath: /var/run/secrets/aws, readOnly: true}
      volumes:
        - name: tmp
          emptyDir: {}
        - name: aws-token
          projected:
            sources:
              - serviceAccountToken: {audience: sts.amazonaws.com, expirationSeconds: 3600, path: token}
EOF

# Wait for either outcome; `kubectl wait` can only wait for one condition at a time.
for _ in $(seq 1 120); do
  status="$(kubectl get job/"$name" -n fleet -o jsonpath='{.status.succeeded}/{.status.failed}')"
  case "$status" in 1/*|*/1) break ;; esac
  sleep 2
done
kubectl logs -n fleet job/"$name" | grep -E "AWS identity|Read hour|Replay of|ERROR|Exception" || true
case "$(kubectl get job/"$name" -n fleet -o jsonpath='{.status.succeeded}')" in
  1) ok "Replay verified." ;;
  *) die "Replay failed. Full log: kubectl logs -n fleet job/$name" ;;
esac
