# Project rules

## Scope, isolation and authority

Use the human-approved task and preserve unrelated work. Read applicable nested instructions before changing their scope; report conflicting contracts. Work in the approved task checkout. Do not modify the active Obsidian checkout. Ordinary workers do not recruit or delegate; only an explicitly authorized Orchestrator coordinates an approved team, subject to the project-specific contract.

Role prompts do not establish effective sandbox permissions. Reviewers use read-only execution; checks that write belong to the authorized executor or Validator. Keep secrets and sensitive production data out of Git and AI evidence; use synthetic or redacted fixtures.

## Validation and delivery

Use this project's actual build/test definitions and record observed results and skipped checks. Do not invent coverage thresholds, branch protections or installed hooks. A passing check is not acceptance.

Commit, push, PR creation, merge, publication, deployment and external writes require the corresponding human authorization. Never merge or publish implicitly. Cleanup requires a preview and authorization. Preserve the established release workflow rather than inferring it from the GitHub default branch.

## Local standards and maintenance

Keep project facts in AGENTS.md and obligations in this rules index. Reusable processes and roles are owned by ai-engineering-kit; select the relevant installed skill instead of copying it into the project. Update context when source declarations change, distinguishing declared, inherited and observed versions.

Project-specific operation/release sources (paths relative to repository root):

- [azure-pipelines.yml](../azure-pipelines.yml).

## Integration and release boundary

This instruction-only task targets develop, following the existing integration/publishing workflow. Do not merge it directly into the main release line or change branch defaults. Preserve the repository's own promotion process. Push, PR validation or integration merges can invoke platform pipelines; state the actual trigger consequences before delivery. Approval for context files is not authorization to publish an Exchange asset or deploy an application.
