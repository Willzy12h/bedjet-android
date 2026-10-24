# Production release settings — owner action

**Status: NOT APPLIED by this work. Existing GitHub settings and secret scopes are
UNVERIFIED.** This document specifies the owner actions required before the next
production tag. No environment, reviewer, ruleset, variable or secret was changed
on GitHub during this review. Official GitHub guidance was checked on 24 October
2026.

The [Build workflow](../../.github/workflows/build.yml) declares
`environment: production` on the `production` job. That declaration makes the job
use the named environment; it does not configure its protection rules. GitHub can
automatically create a missing environment without reviewers, restrictions or
secrets. The owner must configure and verify the settings below before allowing a
production tag to run. [Environment configuration][1]

## 1. Configure the `production` environment

Open the repository's **Settings → Environments → production**. If it does not
exist, use **New environment**, name it exactly `production`, and configure it.
For a personal repository this requires the owner; for an organisation repository
it requires repository administration access. [Environment configuration][1]

| Setting | Required value |
| --- | --- |
| Environment name | `production` |
| Required reviewers | Enabled; nominate at least one named, trusted reviewer independent of the release initiator. Record the chosen account or team in the owner evidence. |
| Prevent self-review | Enabled. |
| Allow administrators to bypass configured protection rules | Disabled; clear the checkbox. |
| Deployment branches and tags | **Selected branches and tags**. |
| Deployment rule | **Ref type: Tag**, name pattern **`v*`**. |
| Other deployment rules | None: remove every branch rule and any additional tag pattern. |

Save the protection rules. Add the deployment rule using **Add deployment branch
or tag rule**, select **Tag**, enter `v*`, and save it. The tag rule and a branch
rule are distinct settings. [Environment configuration][1]

Reviewers need at least read access. GitHub accepts one approval from the configured
reviewer list, even when several reviewers are listed. Prevent self-review blocks
the workflow initiator from approving; the owner must separately choose a reviewer
who can independently assess the proposed code and release evidence. Confirm the
required controls are available for this repository's visibility and plan; where
they are unavailable, record the production path as blocked pending an owner
decision. [Environment protection behaviour][2]

## 2. Put each input in its required scope

Populate the following exact names. The certificate fingerprint is a configuration
variable; the four keystore inputs are secrets.

| Name | GitHub type and scope | Required value and consumer |
| --- | --- | --- |
| `NOCTURNE_KEYSTORE_BASE64` | **Secret**, `production` environment only | Base64-encoded production keystore. Read by the signing step. |
| `NOCTURNE_KEYSTORE_PASSWORD` | **Secret**, `production` environment only | Password for that keystore. Read by the signing step. |
| `NOCTURNE_KEY_ALIAS` | **Secret**, `production` environment only | Exact production private-key alias. Read by the signing step. |
| `NOCTURNE_KEY_PASSWORD` | **Secret**, `production` environment only | Password for that private key. Read by the signing step. |
| `NOCTURNE_RELEASE_CERT_SHA256` | **Variable**, `production` environment only | Independently established expected production signing-certificate SHA-256, preferably 64 hexadecimal characters. Read by the signer/history preflight and signing-verification steps. |
| `NOCTURNE_RELEASE_VERSION_CODE` | **Repository variable** | Explicit positive decimal version code, at most `2100000000` and greater than every published version code. Read by the earlier `build` job. |

The consumers and validation above are defined in
[build.yml](../../.github/workflows/build.yml) and
[release_artifacts.py](../../.github/workflows/release_artifacts.py).
The version code must remain a repository variable because `build` executes before
the protected production job and does not use the `production` environment.

### Owner migration sequence

1. Complete the environment protections and tag rulesets before the next release
   tag is pushed. Keep release initiation paused while migrating the inputs.
2. In **Settings → Environments → production**, add all four names under
   **Environment secrets** and the certificate fingerprint under **Environment
   variables**, using the approved production source values. Do not copy secret
   values into repository files, review comments or evidence.
3. In repository **Settings → Secrets and variables → Actions**, remove all
   repository-scoped copies of those four secrets and the certificate variable.
   Retain `NOCTURNE_RELEASE_VERSION_CODE` in the **Variables** tab.
4. If an organisation provides any of those five names, remove this repository's
   access to each organisation-scoped value, or remove an obsolete value entirely.
   Where another repository still needs a shared value, narrow its repository
   access policy to an explicit list that excludes Nocturne. An organisation policy
   covering all repositories, or all private repositories where applicable, is
   still broad access and must be narrowed. Review the policy under organisation
   **Settings → Secrets and variables → Actions**. [Secret scope and access][3]
5. Verify that those five names exist only in `production` for this repository.
   Record names, scopes and access policies without secret values. Confirm the
   repository version-code variable remains available to `build`.

GitHub gives an environment secret precedence over the same name at repository or
organisation scope; that precedence does not remove the broader copies. Those
copies would remain available to jobs outside the protected environment and could
also supply a value when the intended environment value is missing. Removing the
copies/access is part of this configuration. Environment secrets become available
only after the environment's required approval. [Secret precedence][4]
[Environment protection behaviour][2]

The workflow resolves `NOCTURNE_RELEASE_CERT_SHA256` in the two consuming **step**
`env` blocks. Keep it there: environment configuration variables become available
after the job starts, so an earlier workflow/job-level lookup is unsuitable for
this environment-scoped input. Missing or malformed expected fingerprints fail the
preflight; the signed APK is subsequently checked against the expected certificate.
[Variable availability][5]

## 3. Protect `v*` tags with two active rulesets

In repository **Settings → Rulesets**, create two **New tag ruleset** entries.
For each, set **Enforcement status: Active**, add the **Target tags** inclusion
pattern `v*`, and leave tag exclusions empty. Use the following separate rule
sets. [Creating rulesets][6]

| Setting | `release-tag-creators` | `release-tags-immutable` |
| --- | --- | --- |
| Target | Tags matching `v*`; no exclusions | Tags matching `v*`; no exclusions |
| Enforcement | Active | Active |
| Restrict creations | Enabled | Disabled |
| Restrict updates | Disabled | Enabled |
| Restrict deletions | Disabled | Enabled |
| Bypass list | Only the narrowly authorised owner/release-manager actor described below | **Empty**, including no administrator, owner, team or app entry |
| Other rules | No additional rule required for this configuration | No additional rule required for this configuration |

The creation ruleset's bypass is the permission to create a release tag. For an
organisation repository, use a dedicated, narrowly populated release-manager team
with the necessary repository access. For a personal repository, use the
repository-administrator role only if it represents the intended owner. Verify
its actual membership rather than assuming the label is narrow. Do not grant
creation bypass to the general write/maintain roles or unrelated apps. Select
**Always allow** for the authorised creation actor; this is a tag operation.
[Creating rulesets][6]

Select only the listed restrictions in each ruleset; in particular, clear any
default deletion restriction from the creation-only ruleset. GitHub's creation,
update and deletion restrictions permit the corresponding operation only to
actors with bypass for that ruleset. [Available tag rules][7]

Keeping immutability in a second ruleset with an empty bypass list means the
creation exception does not also authorise updating or deleting existing `v*`
tags. GitHub aggregates applicable rulesets, so both must be active. This is the
reason for using two rulesets. Owners with settings permissions can still
edit the configuration itself; retain dated evidence of the active settings.
[Ruleset layering][8]

The `v*` pattern is a tag-name pattern, not `refs/tags/v*`. It does not match names
containing a slash. The workflow additionally validates the release name before
building; use a reviewed version tag such as `v0.2.0`. No tag is created by this
document. [Creating rulesets][6]

## 4. Review each production run before approval

The current workflow runs `build` for pushes to `main`, `v*` tag pushes and pull
requests. The temporary `gpt/nocturne-release` push trigger has been removed.
`production` requires a successful `build` and a `v*` tag push; it then enters the
protected environment. `release` depends on successful `production` and holds the
workflow's only `contents: write` permission.

Before approving, the independent reviewer should:

1. Confirm the tag resolves to the intended reviewed commit and the workflow run,
   checked-out source and recorded source provenance identify that same commit.
   Review the workflow and release helpers at the tagged commit as well as the
   application changes. A tag-name restriction does not establish which commit
   was reviewed.
2. Check that the complete `build` job succeeded, including required tests, lint,
   release-signing probes, serialisation, manifest and screenshot gates. Examine
   the artefacts for that run and attempt, including gate outcomes, source hashes
   and unsigned-input metadata. The explicitly labelled `TEST-ONLY` candidate is
   separate from the production input.
3. Confirm the chosen version name/code and independently known production
   certificate match the intended release. The protected job's history check and
   actual signature verification still run after approval and can block signing
   or publication.
4. In the run's **Review deployments** dialogue, select `production`, record the
   reviewed commit and relevant evidence in the comment, and choose **Approve and
   deploy** only when satisfied. Otherwise choose **Reject**. Approval makes the
   environment secrets available to the job; rejection fails the workflow.
   [Reviewing deployments][9]

## 5. Record owner verification separately from code checks

The owner should attach dated, non-secret evidence for each item before marking
the production configuration ready:

- Environment name, required reviewer account/team, self-review prevention and
  disabled administrator bypass.
- Exactly one deployment rule: **Tag `v*`**, with no branch rules.
- Both active tag rulesets, their `v*` target, selected restrictions and respective
  bypass lists.
- The four secret names and fingerprint variable under `production`; absence of
  corresponding repository copies and organisation access; the version-code
  variable still at repository scope.
- For the next authorised release, the actual waiting approval state, independent
  review, run URL, commit and resulting production verification evidence.

Local workflow lint, action pin verification and dependency hash checks validate
repository content. They do not inspect or prove GitHub's environment settings,
secret values, reviewer membership or tag protection. Keep the owner configuration
**UNVERIFIED** until its evidence is supplied. No production release, tag mutation
or GitHub settings change was performed by this work.

## Official references

- [Environment configuration][1]
- [Environment protection behaviour and availability][2]
- [Secret scope and organisation access policies][3]
- [Secret precedence and read timing][4]
- [Configuration-variable availability and precedence][5]
- [Creating tag rulesets and selecting bypass actors][6]
- [Creation, update and deletion restrictions][7]
- [How multiple rulesets apply][8]
- [Approving or rejecting deployments][9]

[1]: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
[2]: https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments
[3]: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets
[4]: https://docs.github.com/en/actions/reference/security/secrets
[5]: https://docs.github.com/en/actions/reference/workflows-and-actions/variables
[6]: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository
[7]: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets
[8]: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets
[9]: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/review-deployments
