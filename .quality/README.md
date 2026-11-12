# Quality gates

CRAP scores cannot rise, new or changed functions stay under 10, and new dryer pairs fail. A reformat alone does not count as a change. Mutation scores cannot drop.

Enable the hook with `git config core.hooksPath .githooks`.

Refresh a baseline with `scripts/quality/refresh-baseline.sh`. Pass `--with-mutation` to include PIT.
