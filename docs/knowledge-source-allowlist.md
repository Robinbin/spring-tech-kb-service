# Knowledge source allowlist (D1)

The ingestion policy currently admits HTTPS pages on `spring.io` and
`docs.spring.io`, plus GitHub repositories whose owner is `spring-projects`.
GitHub sources must identify an approved repository path; all other hosts and
repository owners are rejected. Redirects are disabled by the documentation
crawler so an approved URL cannot redirect ingestion to an unapproved host.

Official Spring documentation and source repositories may be ingested only
with an `OFFICIAL_DOCS` or `APACHE_2` license label and a license URL plus an
approver identity and approval reference on each source record. These required
fields are an audit hook, not independent evidence that legal has approved a
source; the team must supply and verify the actual reviewer and approval record
before production ingestion.

Phase 4's enabled official-document catalog contains only these bounded
reference entry points:

| Catalog key | Official documentation URL | Declared licence evidence |
| --- | --- | --- |
| `spring-cloud` | `https://docs.spring.io/spring-cloud/docs/current/reference/html/` | `https://github.com/spring-projects/spring-cloud/blob/main/LICENSE.txt` (Apache-2.0) |
| `spring-batch` | `https://docs.spring.io/spring-batch/reference/` | `https://github.com/spring-projects/spring-batch/blob/main/LICENSE.txt` (Apache-2.0) |
| `spring-integration` | `https://docs.spring.io/spring-integration/reference/` | `https://github.com/spring-projects/spring-integration/blob/main/LICENSE.txt` (Apache-2.0) |

The `ADMIN`-only `POST /api/knowledge/crawl` operation accepts catalog keys,
not caller-supplied URLs. Each request is capped at three catalog pages, each
response body at 512 KiB, and extracted page text at 12,000 characters. It
requires the authenticated operator identity and an existing legal approval
reference; it does not make unreviewed sources legally approved. Redirects
remain disabled, conditional requests and robots rules remain enforced, and
the normal admission policy and injection detector are applied before indexing.

Third-party blog bodies and AI-generated text are not accepted by the normal
ingestion path. Blog metadata is a separate, per-article workflow and requires
CC BY-SA licensing evidence; the crawler does not ingest article body text.
The D5 original summaries are separately staged in
`src/main/resources/knowledge/d5-review-seed.json`; all entries remain pending
human verification and must not be exposed as approved retrieval content.
