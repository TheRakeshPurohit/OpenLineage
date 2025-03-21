# Copyright 2018-2025 contributors to the OpenLineage project
# SPDX-License-Identifier: Apache-2.0

from openlineage.client import set_producer

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.utils.dates import days_ago

_PRODUCER = "https://github.com/OpenLineage/OpenLineage/tree/0.0.1/integration/airflow"
set_producer(_PRODUCER)


default_args = {
    "owner": "datascience",
    "depends_on_past": False,
    "start_date": days_ago(7),
    "email_on_failure": False,
    "email_on_retry": False,
    "email": ["datascience@example.com"],
}


dag = DAG(
    "source_code_dag",
    schedule_interval="@once",
    default_args=default_args,
    description="Test dag.",
)


def callable():
    print(10)


python_task = PythonOperator(task_id="python_task", python_callable=callable, dag=dag)

bash_task = BashOperator(task_id="bash_task", bash_command="ls -halt && exit 0", dag=dag)

python_task >> bash_task
