import os

import pytest

from app.graph.checkpointer import open_checkpointer
from tests.workflows import SIMPLE, compile_graph, initial_state


@pytest.mark.integration
@pytest.mark.skipif(not os.getenv("RUNTIME_DATABASE_URL"), reason="RUNTIME_DATABASE_URL not set")
def test_execution_state_survives_in_postgres():
    config = {"configurable": {"thread_id": "pg-checkpoint-test"}}
    with open_checkpointer(os.environ["RUNTIME_DATABASE_URL"]) as saver:
        compile_graph(SIMPLE, ["hello"], checkpointer=saver).invoke(initial_state(SIMPLE), config)

    with open_checkpointer(os.environ["RUNTIME_DATABASE_URL"]) as saver:
        restored = compile_graph(SIMPLE, ["unused"], checkpointer=saver)
        assert restored.get_state(config).values["answer"] == "hello"
