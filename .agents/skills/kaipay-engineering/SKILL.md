---
name: kaipay-engineering
description: Execute an approved KaiPay engineering task or bounded task batch using the repository workflow, with independent review, verification, and authorization gates.
---

# KaiPay engineering

Use this skill for a user-authorized KaiPay task or ordered batch. Invocation selects a workflow; it does not authorize implementation, commits, pushes, deployment, or additional backlog tasks.

1. Confirm the current checkout is KaiPay. Read applicable global, repository, and scoped instructions, including [root AGENTS.md](../../../AGENTS.md). Use the actual checkout root. The detailed policy at `docs/engineering/WORKFLOW.md` is optional, locally provisioned, and excluded from public Git. If available, read and follow it; if missing or unreadable, request the intended workflow or permission to provision it before executing an autonomous task batch. Do not assume it is available in a fresh clone; read-only inspection may continue.
2. Inspect current branch, HEAD, working-tree changes, and remotes. Read relevant locally available task records and any approved queue; reconcile them with source and Git history. Treat historical indexes and backlog proposals as context, not permission or proof of current task status.
3. Establish the user's authorization, scope, acceptance criteria, stopping point, and commit/publication permissions from the current request or evidenced batch approval. Resolve routine details internally; ask only for missing decisions that block safe progress. If invocation supplies no actionable task, request one without selecting backlog work.
4. Act as the main-agent orchestrator under the workflow. Delegate investigation, focused implementation, and verification when beneficial, with clear file ownership and no overlapping writes. Obtain read-only independent review from someone other than the implementer; evaluate findings and retain final accountability.
5. Apply the provisioned workflow's lifecycle, risk-based stopping rules, and verification gates. Maintain required task documentation locally within authorized scope. All of `docs/engineering/`, including reports, approval queues, workflow records, and agent notes, stays local-only. Never stage additions/modifications, commit/push content, or force-add internal records; only expressly authorized index removals are allowed, preserving local copies. Before every commit, the orchestrator must inspect the complete staged diff for internal content; before publication, inspect every outgoing commit. Do not create reports or queues when excluded, or bypass failed mandatory gates.
6. Commit or publish only when specifically authorized and all required gates pass. Continue only through the explicitly approved bounded queue in its approved order. Report changes, review/verification evidence and limits, Git/publication state, and remaining blockers; stop at the authorized endpoint.

Task completion does not authorize automatic documentation publication. Public architecture and user-facing documentation may be updated when authorized; internal reports are not public delivery artifacts and their contents or private links must not be copied into public documentation. Preserve protected CODEX_KAIPAY documents and the independent Learning Lab boundary under root AGENTS.md. Keep detailed policy in the local workflow.
