# ADR 0001 — What runs in AWS, at $0

**Status:** accepted · 2026-09-10 · S20

## Context

M8 asks for a cloud presence: a public HTTPS URL, real IAM, events archived in S3. It also asks for
a bill of **$0.00**, not "cheap". That constraint is a design input, not something to report
afterwards, and it decides more about what goes to AWS than any technical preference.

Three facts shape it.

**AWS's free tier changed in July 2025.** Accounts opened since then choose a *Free plan* or a *Paid
plan*. Both come with $100–$200 of credits. On the Free plan AWS cannot charge a card at all, but
the account closes after six months (or when the credits run out), and its data is deleted 90 days
later. The Paid plan does not expire. A Free-plan account can be upgraded at any time and keeps its
credits. This project's account was opened on the Free plan on 2026-09-10.

**Credits make the bill lie.** On either plan, usage is paid from credits first, so the billing
console reads $0.00 while real resources are being consumed. "The bill says zero" therefore proves
nothing while credits remain. Only usage that stays within the **always-free** allowances, which
never expire and need no credits, is $0 in the sense this project means.

**The always-free allowances contain no general-purpose compute.** Lambda (1M requests and 400,000
GB-seconds a month), CloudFront (1 TB and 10M requests), DynamoDB (25 GB), IAM, and budget
monitoring are always free. EC2 is not. Before July 2025 it was free for twelve months; since then
it is paid from credits. S3's status is reported inconsistently: some sources list 5 GB as always
free, and others list it only as a twelve-month or credit-funded offer. This design keeps S3 usage in
the kilobytes-to-megabytes range, where either reading rounds to $0.00.

## Decision

**The platform keeps running where it runs now, on the Kind cluster. AWS receives what the platform
concludes and serves it to the public.** Specifically:

| In AWS | Why it can be $0 |
|---|---|
| IAM: an OIDC provider and a role GitHub Actions assumes | IAM is free |
| A zero-spend budget, counting gross cost with credits excluded | Budgets without actions are free |
| S3: Terraform state (S20), the event archive (S21) | Kilobytes to megabytes, written in batches |
| Lambda behind CloudFront: the public demo (S22) | Inside the always-free request allowances, by orders of magnitude |

| Not in AWS | Approximate list price | What it would have bought |
|---|---|---|
| EKS | ~$73/month for the control plane alone, before any node | The same Kubernetes the laptop already runs |
| EC2 for a self-managed cluster | Credit-funded only; plus ~$3.65/month per public IPv4 address | Somewhere to run Kafka and MongoDB |
| MSK (managed Kafka) | Tens to hundreds of dollars a month, depending on the model | A broker the project already runs in one pod |
| NAT gateway | ~$33–41/month plus data processing | Outbound internet for private subnets, which exist only if the above do |
| Application Load Balancer | ~$16/month plus capacity units | A front door; CloudFront provides one for free |
| A customer-managed KMS key | $1/month each, used or not | Encryption S3 already applies with its own keys (SSE-S3) |

**There is therefore no cloud overlay.** `deploy/overlays/local` and `deploy/overlays/gitops` stay the
only two. A third would need a cluster to apply to, and the only $0 cluster is the one on the laptop.

**The budget counts gross cost.** `include_credit = false`. An alert at the first cent of real
usage is what distinguishes "within the always-free allowance" from "being quietly paid for by
credits that will run out".

**No human uses long-lived AWS keys, and CI uses none at all.** GitHub Actions assumes a role
through OIDC, scoped to pushes to `main` of this repository. The laptop signs in with `aws login`,
which issues short-lived credentials from a console session. Access keys on disk are the thing this
milestone exists to avoid.

## Consequences

- The public demo is only as live as the laptop. When the laptop is off, the archive and anything
  served from it stop advancing, but they stay up. That is an honest description of a portfolio
  project at $0, and a better one than a cloud copy that quietly costs money.
- Getting events from the laptop into S3 is the platform's first *outbound* write to AWS from
  somewhere that is not CI. The identity that write uses is S21's problem. It must not be a
  long-lived key either.
- Before 2027-03-10 the account must either be upgraded to the Paid plan or be allowed to close.
  If it closes, `scripts/infra-down.sh` should be run first, so that the closure removes nothing that
  Terraform still believes exists.
- Every AWS resource carries `Project = fleet-tracker` through provider default tags, so "everything
  this project owns" is a filter in the console rather than a list somebody maintains.

## Alternatives rejected

- **Spend the credits.** Up to $200 would run EKS for about two months. It would demonstrate that a
  credit balance can be spent, then turn into a bill or a closed account, and the public URL would
  die with it.
- **Run the whole platform on one EC2 instance under credits.** Same problem, over a longer fuse.
  It would also move the project from "the cluster on this laptop" to "a cluster nobody watches".
- **The Paid plan from day one.** It is the only plan that outlives six months, but it lets a
  mistake reach a card. Starting on the Free plan and upgrading deliberately, with the budget already
  in place, puts that decision after the guard rather than before it.
