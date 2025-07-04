# Copyright 2018-2025 contributors to the OpenLineage project
# SPDX-License-Identifier: Apache-2.0
import datetime
import json
from enum import Enum
from typing import Dict
from unittest import mock

import attr
import pytest
import yaml
from openlineage.common.provider.dbt.processor import Adapter
from openlineage.common.provider.dbt.structured_logs import DbtStructuredLogsProcessor
from openlineage.common.provider.dbt.utils import DBT_LOG_FILE_MAX_BYTES
from openlineage.common.test import match
from openlineage.common.utils import get_from_nullable_chain

###########
# helpers
###########
DUMMY_UUID_4 = "e2c4a0ab-d119-4828-b9c4-96ffd4c79d4f"
DUMMY_RANDOM_LOG_FILE = "dbt-logs-e2c4a0ab-d119-4828-b9c4-96ffd4c79d4f"


def ol_event_to_dict(event) -> Dict:
    return attr.asdict(event, value_serializer=serialize)


def serialize(inst, field, value):
    if isinstance(value, Enum):
        return value.value
    return value


##################
# fixtures
##################


@pytest.fixture(autouse=True)
def patch_get_dbt_profiles_dir(monkeypatch):
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.structured_logs.get_dbt_profiles_dir",
        lambda *args, **kwargs: "./tests/dbt/structured_logs",
    )


##################
# test functions
##################


@pytest.mark.parametrize(
    "target, command_line, logs_path, expected_ol_events_path, manifest_path",
    [
        # successful postgres run
        (
            "postgres",
            ["dbt", "run", "..."],
            "./tests/dbt/structured_logs/postgres/run/logs/successful_run_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/run/results/successful_run_ol_events.json",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        # failed postgres run. Model has SQL error in it
        (
            "postgres",
            ["dbt", "run", "..."],
            "./tests/dbt/structured_logs/postgres/run/logs/failed_run_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/run/results/failed_run_ol_events.json",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        # successful snowflake run
        (
            "snowflake",
            ["dbt", "run", "..."],
            "./tests/dbt/structured_logs/snowflake/run/logs/successful_run_logs.jsonl",
            "./tests/dbt/structured_logs/snowflake/run/results/successful_run_ol_events.json",
            "./tests/dbt/structured_logs/snowflake/run/target/manifest.json",
        ),
        # failed snowflake run
        (
            "snowflake",
            ["dbt", "run", "..."],
            "./tests/dbt/structured_logs/snowflake/run/logs/failed_run_logs.jsonl",
            "./tests/dbt/structured_logs/snowflake/run/results/failed_run_ol_events.json",
            "./tests/dbt/structured_logs/snowflake/run/target/manifest.json",
        ),
        # postgres seed
        (
            "postgres",
            ["dbt", "seed", "..."],
            "./tests/dbt/structured_logs/postgres/seed/logs/seed_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/seed/results/seed_ol_events.json",
            "./tests/dbt/structured_logs/postgres/seed/target/manifest.json",
        ),
        # snowflake seed
        (
            "snowflake",
            ["dbt", "seed", "..."],
            "./tests/dbt/structured_logs/snowflake/seed/logs/seed_logs.jsonl",
            "./tests/dbt/structured_logs/snowflake/seed/results/seed_ol_events.json",
            "./tests/dbt/structured_logs/snowflake/seed/target/manifest.json",
        ),
        # postgres snapshot
        (
            "postgres",
            ["dbt", "snapshot", "..."],
            "./tests/dbt/structured_logs/postgres/snapshot/logs/snapshot_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/snapshot/results/snapshot_ol_events.json",
            "./tests/dbt/structured_logs/postgres/snapshot/target/manifest.json",
        ),
        # snowflake snapshot
        (
            "snowflake",
            ["dbt", "snapshot", "..."],
            "./tests/dbt/structured_logs/snowflake/snapshot/logs/snapshot_logs.jsonl",
            "./tests/dbt/structured_logs/snowflake/snapshot/results/snapshot_ol_events.json",
            "./tests/dbt/structured_logs/snowflake/snapshot/target/manifest.json",
        ),
        # postgres test
        (
            "postgres",
            ["dbt", "test", "..."],
            "./tests/dbt/structured_logs/postgres/test/logs/test_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/test/results/test_ol_events.json",
            "./tests/dbt/structured_logs/postgres/test/target/manifest.json",
        ),
        # postgres dbt tests on sources
        (
            "postgres",
            ["dbt", "test", "..."],
            "./tests/dbt/structured_logs/postgres/test_source/logs/test_source_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/test_source/results/test_source_ol_events.json",
            "./tests/dbt/structured_logs/postgres/test_source/target/manifest.json",
        ),
        # postgres build
        (
            "postgres",
            ["dbt", "build", "..."],
            "./tests/dbt/structured_logs/postgres/build_command/logs/build_logs.jsonl",
            "./tests/dbt/structured_logs/postgres/build_command/results/build_ol_events.json",
            "./tests/dbt/structured_logs/postgres/build_command/target/manifest.json",
        ),
    ],
    ids=[
        # run command
        "postgres_successful_dbt_run",
        "postgres_failed_dbt_run",
        "snowflake_successful_dbt_run",
        "snowflake_failed_dbt_run",
        # seed command
        "postgres_dbt_seed",
        "snowflake_dbt_seed",
        # snapshot command
        "postgres_dbt_snapshot",
        "snowflake_dbt_snapshot",
        # test command
        "postgres_dbt_test",
        "postgres_dbt_test_source",
        # build command
        "postgres_dbt_build",
    ],
)
@mock.patch("openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command")
def test_parse(mock_dbt_run_command, target, command_line, logs_path, expected_ol_events_path, manifest_path):
    def parsed():
        processor.received_dbt_command_completed = True
        return open(logs_path).readlines()

    mock_dbt_run_command.side_effect = parsed

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target=target,
        dbt_command_line=command_line,
    )

    processor.manifest_path = manifest_path

    actual_ol_events = list(ol_event_to_dict(event) for event in processor.parse())
    expected_ol_events = json.load(open(expected_ol_events_path))

    assert match(expected=expected_ol_events, result=actual_ol_events, ordered_list=True)


@pytest.mark.parametrize(
    "target, expected_adapter_type",
    [
        ("postgres", Adapter.POSTGRES),
        ("snowflake", Adapter.SNOWFLAKE),
    ],
    ids=["postgres", "snowflake"],
)
@mock.patch("openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command")
def test_adapter_type(mock_dbt_run_command, target, expected_adapter_type):
    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target=target,
        dbt_command_line=["dbt", "run", "..."],
    )

    def parsed():
        processor.received_dbt_command_completed = True
        return []

    mock_dbt_run_command.side_effect = parsed

    try:
        next(processor.parse())
    except StopIteration:
        pass

    assert processor.adapter_type == expected_adapter_type


@pytest.mark.parametrize(
    "target, expected_dataset_namespace",
    [
        ("postgres", "postgres://postgres:5432"),
        ("snowflake", "snowflake://mysnowflakeaccount.us-east-1.aws"),
    ],
    ids=["postgres", "snowflake"],
)
@mock.patch("openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command")
def test_dataset_namespace(mock_run_dbt_command, target, expected_dataset_namespace):
    def parsed():
        processor.received_dbt_command_completed = True
        return []

    mock_run_dbt_command.side_effect = parsed

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target=target,
        dbt_command_line=["dbt", "run", "..."],
    )

    try:
        next(processor.parse())
    except StopIteration:
        pass

    assert processor.dataset_namespace == expected_dataset_namespace


@pytest.mark.parametrize(
    "dbt_log_events, expected_ol_events, dbt_event_type, manifest_path",
    [
        (
            "./tests/dbt/structured_logs/postgres/events/logs/MainReportVersion.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/MainReportVersion_OL.yaml",
            "MainReportVersion",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_CommandCompleted.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_CommandCompleted_OL.yaml",
            "CommandCompleted",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/failed_CommandCompleted.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/failed_CommandCompleted_OL.yaml",
            "CommandCompleted",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/NodeStart.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/NodeStart_OL.yaml",
            "NodeStart",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_NodeFinished.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_NodeFinished_OL.yaml",
            "NodeFinished",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/failed_NodeFinished.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/failed_NodeFinished_OL.yaml",
            "NodeFinished",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/SQLQuery.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/SQLQuery_OL.yaml",
            "SQLQuery",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_SQLQueryStatus.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_SQLQueryStatus_OL.yaml",
            "SQLQueryStatus",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/failed_SQLQueryStatus.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/failed_SQLQueryStatus_OL.yaml",
            "SQLQueryStatus",
            "./tests/dbt/structured_logs/postgres/run/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/failed_test_NodeFinished.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/failed_test_NodeFinished_OL.yaml",
            "NodeFinished",
            "./tests/dbt/structured_logs/postgres/test/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_test_NodeFinished.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_test_NodeFinished_OL.yaml",
            "NodeFinished",
            "./tests/dbt/structured_logs/postgres/test/target/manifest.json",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/skipped_test_NodeFinished.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/skipped_test_NodeFinished_OL.yaml",
            "NodeFinished",
            "./tests/dbt/structured_logs/postgres/test/target/manifest.json",
        ),
    ],
    ids=[
        "MainReportVersion",
        "successful_CommandCompleted",
        "failed_CommandCompleted",
        "NodeStart",
        "successful_NodeFinished",
        "failed_NodeFinished",
        "SQLQuery",
        "successful_SQLQueryStatus",
        "failed_SQLQueryStatus",
        "failed_test_NodeFinished",
        "successful_test_NodeFinished",
        "skipped_test_NodeFinished",
    ],
)
@mock.patch("openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command")
def test_parse_dbt_events(
    mock_dbt_run_command, dbt_log_events, expected_ol_events, dbt_event_type, manifest_path
):
    """
    This tests:
    1. the parent/child relationship of OL events
    2. the runId remains the same for the START/COMPLETE/FAIL OL events
    """

    def parsed():
        processor.received_dbt_command_completed = True
        return [json.dumps(dbt_event) for dbt_event in yaml.safe_load(open(dbt_log_events))]

    mock_dbt_run_command.side_effect = parsed

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )
    processor.manifest_path = manifest_path
    actual_ol_events = list(ol_event_to_dict(event) for event in processor.parse())
    expected_ol_events = yaml.safe_load(open(expected_ol_events))

    assert match(expected=expected_ol_events, result=actual_ol_events, ordered_list=True)

    command_start_event = actual_ol_events[0]

    if dbt_event_type == "CommandCompleted":
        command_completed_event = actual_ol_events[1]

        assert command_start_event["run"]["runId"] == command_completed_event["run"]["runId"]

    elif dbt_event_type == "NodeStart":
        node_start_event = actual_ol_events[1]

        assert command_start_event["run"]["runId"] == get_from_nullable_chain(
            node_start_event, "run.facets.parent.run.runId".split(".")
        )

    elif dbt_event_type == "NodeFinished":
        node_start_event = actual_ol_events[1]
        node_finished_event = actual_ol_events[2]

        assert node_start_event["run"]["runId"] == node_finished_event["run"]["runId"]

    elif dbt_event_type == "SQLQuery":
        node_start_event = actual_ol_events[1]
        sql_query_start_event = actual_ol_events[2]

        assert node_start_event["run"]["runId"] == get_from_nullable_chain(
            sql_query_start_event, "run.facets.parent.run.runId".split(".")
        )

    elif dbt_event_type == "SQLQueryStatus":
        node_start_event = actual_ol_events[1]
        sql_query_start_event = actual_ol_events[2]
        sql_query_status_event = actual_ol_events[3]

        assert sql_query_start_event["run"]["runId"] == sql_query_status_event["run"]["runId"]
        assert node_start_event["run"]["runId"] == get_from_nullable_chain(
            sql_query_status_event, "run.facets.parent.run.runId".split(".")
        )


@pytest.mark.parametrize(
    "dbt_event, expected_job_name, dbt_command",
    [
        # model
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {"alias": "orders", "database": "postgres", "schema": "public"},
                        "resource_type": "model",
                        "unique_id": "model.jaffle_shop.orders",
                    }
                }
            },
            "model.jaffle_shop.orders",
            "run",
        ),
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {"alias": "orders", "database": "postgres", "schema": "public"},
                        "resource_type": "model",
                        "unique_id": "model.jaffle_shop.orders",
                    }
                }
            },
            "model.jaffle_shop.orders",
            "build",
        ),
        # snapshot
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {"alias": "orders", "database": "postgres", "schema": "public"},
                        "resource_type": "snapshot",
                        "unique_id": "snapshot.jaffle_shop.orders",
                    }
                }
            },
            "snapshot.jaffle_shop.orders",
            "snapshot",
        ),
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {"alias": "orders", "database": "postgres", "schema": "public"},
                        "resource_type": "snapshot",
                        "unique_id": "snapshot.jaffle_shop.orders",
                    }
                }
            },
            "snapshot.jaffle_shop.orders",
            "build",
        ),
        # test
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {
                            "alias": "not_null_customers_customer_id",
                            "database": "postgres",
                            "schema": "public_dbt_test__audit",
                        },
                        "resource_type": "test",
                        "unique_id": "test.jaffle_shop.not_null_customers_customer_id.5c9bf9911d",
                    }
                }
            },
            "test.jaffle_shop.not_null_customers_customer_id.5c9bf9911d",
            "test",
        ),
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {
                            "alias": "not_null_customers_customer_id",
                            "database": "postgres",
                            "schema": "public_dbt_test__audit",
                        },
                        "resource_type": "test",
                        "unique_id": "test.jaffle_shop.not_null_customers_customer_id.5c9bf9911d",
                    }
                }
            },
            "test.jaffle_shop.not_null_customers_customer_id.5c9bf9911d",
            "build",
        ),
        # seeds
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {
                            "alias": "raw_customers",
                            "database": "postgres",
                            "schema": "public",
                        },
                        "resource_type": "seed",
                        "unique_id": "seed.jaffle_shop.raw_customers",
                    }
                }
            },
            "seed.jaffle_shop.raw_customers",
            "seed",
        ),
        (
            {
                "data": {
                    "node_info": {
                        "node_relation": {
                            "alias": "raw_customers",
                            "database": "postgres",
                            "schema": "public",
                        },
                        "resource_type": "seed",
                        "unique_id": "seed.jaffle_shop.raw_customers",
                    }
                }
            },
            "seed.jaffle_shop.raw_customers",
            "build",
        ),
    ],
    ids=["run_mode", "build_model", "snapshot", "build_snapshot", "test", "build_test", "seed", "build_seed"],
)
def test_node_job_name(dbt_event, expected_job_name, dbt_command):
    """
    Tests the OL job name given to every dbt node type
    """
    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )
    processor.dbt_command = dbt_command

    actual_job_name = processor._get_job_name(dbt_event)

    assert expected_job_name == actual_job_name


@pytest.mark.parametrize(
    "dbt_events, expected_sql_job_names",
    [
        (
            [
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_orders"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T19:45:53.117597Z"},
                },
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_orders"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T20:45:53.117597Z"},
                },
            ],
            [
                "model.jaffle_shop.stg_orders.sql.1",
                "model.jaffle_shop.stg_orders.sql.2",
            ],
        ),
        (
            [
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_payments"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T19:45:53.117597Z"},
                },
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_payments"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T20:45:53.117597Z"},
                },
                # the same as the first one
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_payments"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T19:45:53.117597Z"},
                },
            ],
            [
                "model.jaffle_shop.stg_payments.sql.1",
                "model.jaffle_shop.stg_payments.sql.2",
                "model.jaffle_shop.stg_payments.sql.1",
            ],
        ),
        (
            [
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_payments"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T19:45:53.117597Z"},
                },
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_payments"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T20:45:53.117597Z"},
                },
                # on a different node_id
                {
                    "data": {
                        "node_info": {"unique_id": "model.jaffle_shop.stg_orders"},
                        "sql": "SELECT * ...",
                    },
                    "info": {"ts": "2024-11-20T19:45:53.117597Z"},
                },
            ],
            [
                "model.jaffle_shop.stg_payments.sql.1",
                "model.jaffle_shop.stg_payments.sql.2",
                "model.jaffle_shop.stg_orders.sql.1",
            ],
        ),
    ],
    ids=["without_duplicates", "with_duplicate_sql", "with_different_node_ids"],
)
def test_sql_job_name(dbt_events, expected_sql_job_names):
    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )

    actual_sql_job_names = [processor._get_sql_job_name(event) for event in dbt_events]

    assert actual_sql_job_names == expected_sql_job_names


@pytest.mark.parametrize(
    "dbt_log_events, expected_ol_events, parent_id_env_var",
    [
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_CommandCompleted.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_CommandCompleted_OL.yaml",
            f"my_parent_namespace/my_parent_job_name/{DUMMY_UUID_4}",
        ),
        (
            "./tests/dbt/structured_logs/postgres/events/logs/successful_CommandCompleted.yaml",
            "./tests/dbt/structured_logs/postgres/events/results/successful_CommandCompleted_OL.yaml",
            "",
        ),
    ],
    ids=["with_env_var", "without_env_var"],
)
def test_parent_run_metadata(dbt_log_events, expected_ol_events, parent_id_env_var, monkeypatch):
    monkeypatch.setenv("OPENLINEAGE_PARENT_ID", parent_id_env_var)
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command",
        lambda self: [json.dumps(dbt_event) for dbt_event in yaml.safe_load(open(dbt_log_events))],
    )

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )
    processor.manifest_path = "./tests/dbt/structured_logs/postgres/run/target/manifest.json"
    actual_ol_events = list(ol_event_to_dict(event) for event in processor.parse())
    expected_ol_events = yaml.safe_load(open(expected_ol_events))

    assert match(expected=expected_ol_events, result=actual_ol_events, ordered_list=True)


@mock.patch("datetime.datetime", wraps=datetime.datetime)
def test_missing_command_completed(mock_dt, monkeypatch):
    missing_command_completed = (
        "./tests/dbt/structured_logs/postgres/events/logs/missing_command_completed.yaml"
    )
    missing_command_completed_ol_events = (
        "./tests/dbt/structured_logs/postgres/events/results/missing_command_completed_OL.yaml"
    )
    mock_dt.now.return_value = datetime.datetime(2024, 1, 1, 0, 0, 0, 1)
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._run_dbt_command",
        lambda self: [json.dumps(dbt_event) for dbt_event in yaml.safe_load(open(missing_command_completed))],
    )

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )
    processor.manifest_path = "./tests/dbt/structured_logs/postgres/run/target/manifest.json"
    actual_ol_events = list(ol_event_to_dict(event) for event in processor.parse())
    expected_ol_events = yaml.safe_load(open(missing_command_completed_ol_events))
    assert match(expected=expected_ol_events, result=actual_ol_events, ordered_list=True)


@pytest.mark.parametrize(
    "dbt_process_return_code, expected_processor_return_code",
    [(0, 0), (1, 1), (2, 2)],
    ids=["success", "failure", "error"],
)
def test_run_dbt_command(dbt_process_return_code, expected_processor_return_code, monkeypatch):
    popen_mock = mock.Mock()
    process_mock = mock.Mock()
    monkeypatch.setattr("openlineage.common.provider.dbt.structured_logs.subprocess.Popen", popen_mock)
    monkeypatch.setattr("openlineage.common.provider.dbt.structured_logs.IncrementalFileReader", mock.Mock())
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._open_dbt_log_file",
        mock.Mock(),
    )
    monkeypatch.setattr("os.stat", mock.Mock())
    popen_mock.return_value = process_mock
    process_mock.returncode = dbt_process_return_code
    process_mock.poll.return_value = 1

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=["dbt", "run", "..."],
    )
    processor.manifest_path = "./tests/dbt/structured_logs/postgres/run/target/manifest.json"
    processor.received_dbt_command_completed = True

    list(processor._run_dbt_command())

    assert expected_processor_return_code == processor.dbt_command_return_code


@pytest.mark.parametrize(
    "input_dbt_command_line, expected_dbt_command_line",
    [
        (
            [
                "dbt",
                "run",
                "--select",
                "orders",
                "--vars",
                "{'foo': 'bar'}",
                "--profiles-dir",
                "my_profiles_dir",
            ],
            [
                "dbt",
                "run",
                "--select",
                "orders",
                "--vars",
                "{'foo': 'bar'}",
                "--profiles-dir",
                "my_profiles_dir",
                "--log-format-file",
                "json",
                "--log-level-file",
                "debug",
                "--log-path",
                "./" + DUMMY_RANDOM_LOG_FILE,
                "--log-file-max-bytes",
                DBT_LOG_FILE_MAX_BYTES,
                "--write-json",
            ],
        ),
        (
            [
                "dbt",
                "run",
                "--select",
                "orders",
                "--vars",
                "{'foo': 'bar'}",
                "--profiles-dir",
                "my_profiles_dir",
                "--log-path",
                "dbt-logs-1234",
            ],
            [
                "dbt",
                "run",
                "--select",
                "orders",
                "--vars",
                "{'foo': 'bar'}",
                "--profiles-dir",
                "my_profiles_dir",
                "--log-path",
                "dbt-logs-1234",
                "--log-format-file",
                "json",
                "--log-level-file",
                "debug",
                "--log-file-max-bytes",
                DBT_LOG_FILE_MAX_BYTES,
                "--write-json",
            ],
        ),
    ],
    ids=["with_no_log_path", "with_log_path"],
)
def test_executed_dbt_command_line(input_dbt_command_line, expected_dbt_command_line, monkeypatch):
    popen_mock = mock.Mock()
    monkeypatch.setattr("openlineage.common.provider.dbt.structured_logs.subprocess.Popen", popen_mock)
    monkeypatch.setattr("openlineage.common.provider.dbt.structured_logs.IncrementalFileReader", mock.Mock())
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.structured_logs.DbtStructuredLogsProcessor._open_dbt_log_file",
        mock.Mock(),
    )
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.utils.generate_random_log_file_name", lambda: DUMMY_RANDOM_LOG_FILE
    )
    monkeypatch.setattr("os.stat", mock.Mock())

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=input_dbt_command_line,
    )
    processor.manifest_path = "./tests/dbt/structured_logs/postgres/run/target/manifest.json"
    processor.received_dbt_command_completed = True

    list(processor._run_dbt_command())

    actual_command_line = popen_mock.call_args[0][0]
    assert actual_command_line == expected_dbt_command_line


@pytest.mark.parametrize(
    "input_dbt_command_line, expected_dbt_log_file_path",
    [
        (
            ["dbt", "run", "--select", "orders", "--project-dir", "my-dbt-project"],
            f"my-dbt-project/{DUMMY_RANDOM_LOG_FILE}/dbt.log",
        ),
        (
            [
                "dbt",
                "run",
                "--select",
                "orders",
                "--project-dir",
                "my-dbt-project",
                "--log-path",
                "dbt-logs-1234",
            ],
            "dbt-logs-1234/dbt.log",
        ),
        (
            ["dbt", "run", "--select", "orders", "--log-path", "dbt-logs-1234"],
            "dbt-logs-1234/dbt.log",
        ),
        (
            ["dbt", "run", "--select", "orders", "--log-path", "/tmp/dbt/dbt-logs-1234"],
            "/tmp/dbt/dbt-logs-1234/dbt.log",
        ),
    ],
    ids=["with_no_log_path", "with_log_path", "without_project_dir", "fully_qualified_path"],
)
def test_logfile_path(input_dbt_command_line, expected_dbt_log_file_path, monkeypatch):
    monkeypatch.setattr(
        "openlineage.common.provider.dbt.utils.generate_random_log_file_name", lambda: DUMMY_RANDOM_LOG_FILE
    )

    processor = DbtStructuredLogsProcessor(
        producer="https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/dbt",
        job_namespace="dbt-test-namespace",
        project_dir="tests/dbt/structured_logs",
        target="postgres",
        dbt_command_line=input_dbt_command_line,
    )
    processor.manifest_path = "./tests/dbt/structured_logs/postgres/run/target/manifest.json"

    assert processor.dbt_log_file_path == expected_dbt_log_file_path
