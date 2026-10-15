"""The release gate: every governance evaluation case must hold (see evals/run.py). A side
effect that happens when a case says it must not fails the build."""

from evals.run import run_all, summarize


def test_every_governance_case_holds():
    outcomes = run_all()

    failures = [f"{o.id}: {'; '.join(o.problems)}" for o in outcomes if not o.passed]
    assert failures == []
    summary = summarize(outcomes)
    assert summary["unsafe_executions"] == 0
    assert summary["cases"] >= 15  # the dataset was not quietly emptied
