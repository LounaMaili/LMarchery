.PHONY: status check test review diff

status:
	git status --short

check:
	./scripts/dev-check.sh

test:
	./gradlew test

review:
	./scripts/codex-review.sh

diff:
	git diff --stat
	git diff
