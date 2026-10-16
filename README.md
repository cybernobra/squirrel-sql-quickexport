# squirrel-sql-quickexport

A [SquirrelSQL](https://github.com/squirrel-sql-client) plugin that exports a SQL result in one click, without the
export dialog, to an automatically named file.

## What it does

Right-click on a result table → **Quick export (last parameters)**, right below the native
**Export CSV / MS Excel / XML / JSON...** entry. 
Also available in the **Session** menu or shortcut **Ctrl+Shift+E**.

The export reuses the last parameters of the native export dialog:

- **directory**: the one of the last native export (a folder chooser is shown the very first time);
- **format and options**: CSV / XLSX / XLS / XML / JSON, headers, fonts, SQL statement sheet, separator...;
- **extension**: added automatically according to the format.

The file name is built from the SQL of the result and the query execution time:
`SCHEMA_TABLE_yyyyMMdd_HHmmss.<ext>`.

| SQL | File name |
|---|---|
| `SELECT * FROM MYLIB/ORDERS WHERE ...` | `MYLIB_ORDERS_20261015_143012.xlsx` |
| `... FROM SALES.ORDERS o JOIN SALES.CUSTOMERS c ON ...` | `SALES_ORDERS_CUSTOMERS_20261015_143012.xlsx` |
| `WITH all_data AS (...) SELECT * FROM all_data` | `ALL_DATA_20261015_143012.xlsx` |
| `SELECT * FROM (... UNION ALL ...) AS merged` | `MERGED_20261015_143012.xlsx` |
| no table found (`VALUES ...`) | `SQLRESULT_20261015_143012.xlsx` |

- Tables are grouped by schema, each schema written once; both `SCHEMA.TABLE` and the AS400 system syntax
  `SCHEMA/TABLE` are recognized.
- A CTE or a derived table alias names the file instead of the tables it reads: choose a meaningful alias.
- Exporting the same result again overwrites the file (same execution time); re-running the query gives a new file.
- The complete table is always exported, never just the selection.

## Requirements

SquirrelSQL 5.0.0 (Java 17+). The plugin relies on SquirrelSQL internal export classes and may need to be rebuilt
for another SquirrelSQL version.

## Build

```bash
mvn package -Dsquirrel.home=/path/to/squirrelsql
```

`squirrel.home` defaults to the `SQUIRREL_SQL_HOME` environment variable. The build produces `target/quickexport.jar`.

## Install

Copy `quickexport.jar` into the `plugins` directory of SquirrelSQL and restart SquirrelSQL.

## License

[GNU General Public License v3.0 or later](LICENSE).
