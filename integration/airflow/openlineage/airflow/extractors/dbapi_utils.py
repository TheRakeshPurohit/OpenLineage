from contextlib import closing
from typing import Dict, Optional, TYPE_CHECKING, List

from openlineage.common.dataset import Source, Dataset
from openlineage.common.models import DbTableSchema, DbColumn
from openlineage.common.sql import DbTableMeta


if TYPE_CHECKING:
    from airflow.hooks.base import BaseHook


_TABLE_SCHEMA = 0
_TABLE_NAME = 1
_COLUMN_NAME = 2
_ORDINAL_POSITION = 3
# Use 'udt_name' which is the underlying type of column
# (ex: int4, timestamp, varchar, etc)
_UDT_NAME = 4


def get_table_schemas(
    hook: "BaseHook", query: str
) -> Dict[str, DbTableSchema]:

    # Keeps tack of the schema by table.
    schemas_by_table = {}

    with closing(hook.get_conn()) as conn:
        with closing(conn.cursor()) as cursor:
            cursor.execute(query)
            for row in cursor.fetchall():
                table_schema_name: str = row[_TABLE_SCHEMA]
                table_name: DbTableMeta = DbTableMeta(
                    row[_TABLE_NAME]
                )
                table_column: DbColumn = DbColumn(
                    name=row[_COLUMN_NAME],
                    type=row[_UDT_NAME],
                    ordinal_position=row[_ORDINAL_POSITION]
                )

                # Attempt to get table schema
                table_key: str = f"{table_schema_name}.{table_name}"
                table_schema: Optional[DbTableSchema] = schemas_by_table.get(table_key)

                if table_schema:
                    # Add column to existing table schema.
                    schemas_by_table[table_key].columns.append(table_column)
                else:
                    # Create new table schema with column.
                    schemas_by_table[table_key] = DbTableSchema(
                        schema_name=table_schema_name,
                        table_name=table_name,
                        columns=[table_column]
                    )
    return schemas_by_table


def get_datasets_from_schemas(
    database: str,
    source: Source,
    tables: List[DbTableMeta],
    schemas: Dict[str, DbTableSchema]
) -> List[Dataset]:
    in_table_names = set((table.schema + "." if table.schema else "") + table.name for table in tables)  # noqa
    x = [
        Dataset.from_table(
            source=source,
            table_name=schema.table_name.name,
            schema_name=schema.schema_name,
            database_name=database
        ) for table_key, schema in schemas.items() if table_key in in_table_names
    ]
    return x
