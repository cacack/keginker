# Hardware test log

Results of the [hardware test script](../guides/run-hardware-tests.md), one
row per run. The tally of attempts and first-tap successes settles
[C1](../../CONSTITUTION.md#success-criteria) for each release, phone and
board. A release that bumps MINOR or MAJOR needs a logged run on at least one
Android and one iOS phone (see [signing.md](signing.md#cutting-a-release)).

Add a row for each run, newest first. Contributors without write access can
send a run with the **Hardware test run** issue form; a maintainer copies it
here and links the issue.

- **App version:** the release version, or the commit of a development build.
- **Attempts** and **first-tap successes:** from scenario 1.
- **Failure messages and notes:** every message seen in any scenario, word for
  word, plus any scenario whose result differed from the expected one. The
  issue form collects these as two fields; merge them here.
- **Date** and **Tester:** for a run sent with the issue form, the issue's
  date and author.

## Runs

| Date | App version | Phone | OS | Board | Attempts | First-tap successes | Failure messages and notes | Tester |
|---|---|---|---|---|---|---|---|---|

No runs are logged yet.
