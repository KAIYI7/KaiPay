# KaiPay repository instructions

Check applicable global and scoped instructions, the current repository root, branch, HEAD, working tree, and relevant source/Git history before work.

`docs/engineering/WORKFLOW.md` is an optional, locally provisioned internal execution policy. If present, read and follow it before engineering tasks, reviews, documentation changes, or Git/publication operations. If absent, do not assume the full autonomous workflow is available: request the intended policy or permission to provision it before executing an autonomous task batch. Read-only inspection may continue.

The workflow, task documents, and skill invocation do not themselves authorize implementation, commits, publication, or other backlog tasks. Establish authorization and scope from the user's task or explicitly approved bounded batch; honor its stopping point.

All of `docs/engineering/` is internal and local-only, including workflow records, task reports, approval queues, indexes, templates, and future agent notes. Never stage additions/modifications, commit content, push content, or force-add these files or other documents designated local-only in `.gitignore`. Before every commit, the main agent must inspect the complete staged diff for internal content; before publication, check every outgoing commit. Only explicitly authorized index removals may be staged, with local copies preserved. Task completion never triggers automatic documentation publication. Public architecture and user-facing documentation may be updated within authorized scope; keep internal report content and private links out of public artifacts.

Keep KaiPay separate from the independent Learning Lab repository. Do not touch `learning-lab/` or import its material without separate authorization. Preserve local `docs/CODEX_KAIPAY_ARCHITECTURE.md`, `docs/CODEX_KAIPAY_HANDOVER.md`, and `docs/CODEX_KAIPAY_TASKS.md`; do not modify, stage, delete, or publish them.

For an approved task or batch, the optional repository skill is [kaipay-engineering](.agents/skills/kaipay-engineering/SKILL.md), invocable as `$kaipay-engineering`. Its invocation grants no action permissions.

When provisioned locally, consult `docs/engineering/INDEX.md`, relevant task reports, and `docs/engineering/QUEUE.md` with its approval evidence. Historical indexes and proposed queues do not authorize execution. These private records may be absent from a fresh clone and are not public delivery artifacts.
