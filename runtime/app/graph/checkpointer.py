from collections.abc import Iterator
from contextlib import contextmanager

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres import PostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool


@contextmanager
def open_checkpointer(database_url: str) -> Iterator[BaseCheckpointSaver]:
    if not database_url:
        yield InMemorySaver()
        return

    with ConnectionPool(
        database_url,
        max_size=10,
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
    ) as pool:
        saver = PostgresSaver(pool)
        saver.setup()
        yield saver
