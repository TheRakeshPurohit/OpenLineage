# OpenLineage dbt integration

Wrapper script for automatic metadata collection from dbt

## Features

**Metadata**

* Model run lifecycle
* Model inputs / outputs

## Requirements

- [Python >= 3.8](https://www.python.org/downloads)
- [dbt >= 0.20](https://www.getdbt.com/)

Right now, `openlineage-dbt` supports only these dbt adapters:

* `bigquery`
* `snowflake`
* `spark` (`thrift` and `odbc`, but not `local`)
* `redshift`
* `athena`
* `glue`
* `postgres`
* `clickhouse`
* `trino`
* `databricks`
* `sqlserver`
* `dremio`
* `duckdb`

## Installation

```bash
$ pip3 install openlineage-dbt
```

To install from source, run:

```bash
$ pip install .
```

## Configuration

### Transport

`openlineage-dbt` uses the OpenLineage Python client to push data to the OpenLineage backend, so any way of configuring Python client will work here as well:

```ini
OPENLINEAGE_URL=http://localhost:5000
OPENLINEAGE_API_KEY=abc
```

dbt integration-specific environment variables:

* `OPENLINEAGE_NAMESPACE` - set if you are using something other than the `default` namespace for job namespace.

### Logging

In addition to conventional logging approaches, the OpenLineage dbt wrapper script provides an alternative way of configuring its logging behavior. By setting the `OPENLINEAGE_DBT_LOGGING` environment variable, you can establish the logging level for the `openlineage.dbt` and its child modules.

You can also set log level of `dbtol` Python module but this is deprecated.

## Usage

To begin collecting dbt metadata with OpenLineage, replace `dbt run` with `dbt-ol run`.

Additional table and column level metadata will be available if `catalog.json`, a result of running `dbt docs generate`, will be found in the target directory.

If you need to send events without running the job you can use the command `dbt-ol send-events`, it will send the metadata of your last run without running the job.

----
SPDX-License-Identifier: Apache-2.0\
Copyright 2018-2025 contributors to the OpenLineage project