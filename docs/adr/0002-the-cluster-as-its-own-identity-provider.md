# ADR 0002 — How pods on the laptop prove who they are to AWS

**Status:** accepted · 2026-09-11 · S21

## Context

S21's archiver runs in the Kind cluster on the laptop and writes to S3. That is the platform's
first write to AWS from somewhere other than CI, so S20's answer does not apply: GitHub's OIDC
token exists only inside a GitHub Actions job. ADR 0001 left the question open with one
constraint: the answer must not be a long-lived key.

The archiver needs `s3:PutObject` on one prefix of one bucket. A replay needs to read it back. The
question is how a process on a home network, with no public address, gets temporary credentials
for exactly that.

## Decision

**The Kind cluster becomes an OIDC identity provider that AWS trusts, the way it trusts GitHub.**

Kubernetes already signs a short-lived token for any pod that asks, stating which service account
it runs as. AWS STS can exchange such a token for temporary credentials, if it can fetch the public
key that verifies the signature. So:

1. The API server's `service-account-issuer` flag (`deploy/kind-cluster.yaml`) names a public HTTPS
   address: `https://fleet-tracker-oidc.s3.ap-south-1.amazonaws.com`.
2. That S3 bucket holds the issuer's discovery document (written by Terraform) and the cluster's
   public key set (written by `scripts/aws-link.sh`, since it changes whenever the cluster is
   recreated). A bucket policy makes exactly those two objects public, and nothing else.
3. IAM registers the address as an OIDC provider. Two roles trust it, each for exactly one service
   account: `fleet:archiver` may put objects under `archive/`, and `fleet:archive-replay` may list
   and get them. Neither may delete.
4. Each pod mounts a projected token with the audience `sts.amazonaws.com`. The AWS SDK's default
   credential chain finds `AWS_ROLE_ARN` and `AWS_WEB_IDENTITY_TOKEN_FILE` and does the rest,
   renewing both the credentials and (through the kubelet) the token before they expire.

This is the mechanism EKS calls "IAM roles for service accounts", built by hand for a cluster AWS
has never heard of.

## Consequences

- **No credential is stored anywhere.** The only secret involved is the cluster's private signing
  key, which exists whether or not AWS is involved and never leaves the Kind node. A stolen pod
  token is useful for at most an hour, and only as that one service account.
- **Recreating the cluster requires `scripts/aws-link.sh`.** Kind generates a new signing key per
  cluster. Until the new public key is published, STS refuses every token with
  `InvalidIdentityToken`. `stack-up.sh` runs the link step when the AWS CLI is signed in.
- **The issuer flag is fixed at cluster creation**, like a port mapping. Adding it in S21 meant
  recreating the cluster once.
- **The issuer bucket's name is committed, and it is global.** It is written into the cluster
  config, which is committed, whereas the account id is not; so the bucket is named
  `fleet-tracker-oidc` rather than after the account. If the name were taken after a
  `terraform destroy`, a stranger could publish keys under it. That would matter only if this
  account still trusted the provider, and the provider is destroyed with the bucket.
- **Anyone can read the public key.** That is the mechanism working, not a leak: a public key
  verifies signatures and cannot make them.
- **The archiver's destination is environment configuration**, delivered as the
  `archive-destination` ConfigMap from Terraform's outputs, so the kustomize base names no account.
  Until it exists, the archiver waits in `CreateContainerConfigError`, which names the missing
  ConfigMap.

## Alternatives rejected

- **An IAM user with an access key limited to `PutObject` on one prefix.** The simplest option by
  far, and exactly the credential M8 set out not to have. It never expires, it would sit in a
  Kubernetes Secret on a laptop, and it would need rotating by somebody who remembered it existed.
- **IAM Roles Anywhere.** A private certificate authority this project runs, a certificate and
  private key per workload in a Secret, exchanged for temporary credentials. Free as long as the CA
  is not AWS's managed one, and a legitimate pattern for machines outside AWS. But the private key
  on disk is a long-lived credential in certificate form, and it needs AWS's signing-helper binary
  added to the image. The cluster's own tokens do the same job with nothing new to store.
- **Serving the discovery documents from CloudFront** rather than a public bucket. It would keep
  every bucket private, but it is a distribution to create and wait for, in order to serve two
  small files that are meant to be public. It is worth revisiting only if S22's CloudFront
  distribution makes it free to add.
