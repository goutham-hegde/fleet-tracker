# ADR 0007 — Deployment is pulled, and scaling follows consumer lag

**Status:** accepted · 2026-09-10 · S17 and S19 (recorded here in S24)

## Context

The platform runs on a Kind cluster on a laptop behind a home router (ADR 0001). CI runs on GitHub's
machines. Two questions follow from that:

1. **How does a merged commit reach the cluster?** Nothing on the internet can reach the laptop's
   Kubernetes API. `main` is also protected, including against administrators and the Actions
   token, so no bot can commit to it.
2. **When should the platform scale?** The tracking processor is the platform's ceiling: S23
   measured 151.5 stored positions a second on one pod and about 585 on four. Its input topic has 12
   partitions, so at most 12 pods can share the work.

## Decision

**ArgoCD, inside the cluster, pulls the desired state from GitHub. KEDA scales the tracking
processor on Kafka consumer lag.**

- **CI builds and publishes images** to ghcr.io, tagged with the commit SHA and never `latest`, only
  after every test job passes. It then resets an unprotected `deploy` branch to the merged commit
  plus one commit that stamps the new tags, and force-pushes it.
- **ArgoCD follows `deploy`** through the `gitops` overlay, with automatic sync, prune and self-heal.
  A hand edit to the cluster is reverted in under a second. The Application object itself is applied
  once, by hand, by `argocd-up.sh`: something has to make the first move.
- **Nothing triggers CI on `deploy`**, so the stamping commit cannot start a build loop.
- **KEDA scales the tracking processor from 1 to 4 pods** on lag in its consumer group. The autoscaler
  owns the replica count, so ArgoCD is told to ignore that field. A burst that one pod clears within
  one polling loop does not scale anything, by design.
- **The local overlay** (`imagePullPolicy: Never`, images loaded from this laptop) and the `gitops`
  overlay share the simulator and the autoscaler as kustomize components. Only one of the two is ever
  applied to a cluster.

## Consequences

- No credential for the cluster exists anywhere outside it. The only thing the cluster needs from the
  internet is outbound HTTPS to github.com and ghcr.io, and the images are public, so there is no
  pull secret either.
- The repository is an accurate statement of what runs. Deleting a manifest deletes the workload.
- **Merge to running takes about 15 minutes**: roughly 7 of tests, 1 of publishing, and up to
  ArgoCD's polling interval. A push with a webhook would be faster, and a webhook needs an inbound
  route.
- The `deploy` branch has rewritten history by design. Nothing reads it except ArgoCD.
- Lag-based scaling reacts to the thing that matters, positions waiting. It also means a pod count
  says nothing about CPU, and the ceiling of four is a laptop's, not the topic's.
- Pods have memory limits and no CPU limits, so a JVM starting up or collecting garbage is never
  throttled into failing its own probes.

## Alternatives rejected

- **Push: a CI job holding a kubeconfig and running `kubectl apply`.** It needs a route from a GitHub
  runner to the laptop: a forwarded port, a tunnel, or a self-hosted runner. Each one exposes a home
  network's Kubernetes API so that deployment can be a little faster, and the credential would live
  in repository secrets.
- **CI committing the new tags to `main`**, as most GitOps pipelines do. It needs a bypass of the
  branch protection S18 had just built, to make deployment convenient.
- **Scaling on CPU through metrics-server.** CPU and lag rise together until MongoDB slows down.
  Then the consumer falls behind while using *less* CPU, and a CPU autoscaler concludes all is well.
- **A pod per partition from the start (12 replicas).** Twelve JVMs do not fit next to Kafka, MongoDB
  and ArgoCD in 11 GB, and S23 showed the laptop, not the partition count, is the binding limit.
- **An "app of apps" bootstrap.** With a single application it is the same hand-applied first step
  with one more layer.
- **Manual sync, or automated sync without self-heal.** Either lets the cluster and the repository
  drift apart quietly, which is the one thing the design exists to prevent.
