# Interactive console

BroadSQL's standard console gives you command history, line editing, TAB completion, keyboard
shortcuts, colors and a table layout that follows your window. It is on by default
([`activatejline=ON`](settings.md)). Everything on this page is about typing and reading; what a
command does is the same with or without it.

- [Keyboard shortcuts](#keyboard-shortcuts)
- [Entering commands](#entering-commands)
- [History](#history)
- [TAB completion](#tab-completion)
- [Colors, themes and highlighting](#colors-themes-and-highlighting)
- [Table layout and window width](#table-layout-and-window-width)
- [The basic console (`activatejline=OFF`)](#the-basic-console-activatejline-off)

## Keyboard shortcuts

Type `HELP SHORTCUTS;` inside BroadSQL to display these shortcuts in the console. It works with or
without a database connection.

| Key | Effect |
|---|---|
| Enter | Submits the line. A command runs once BroadSQL has read its closing `;` (see [Entering commands](#entering-commands)). |
| Tab | Completes the word being typed (see [TAB completion](#tab-completion)). With several candidates, opens a menu: Tab moves to the next candidate, Shift+Tab to the previous one. At the start of a line it inserts a TAB, for indentation. |
| Esc | Cancels everything you are typing and leaves an empty prompt: the whole line wherever the cursor is, and also the earlier lines of a statement you have not yet ended with `;`. Nothing runs and nothing is added to history. Also works while the completion menu is open, after recalling a command with Up, and during a Ctrl+R search. |
| Up / Down | Previous / next command in history. Editing a recalled command never changes the stored one. |
| Ctrl+R | Searches history backward: type part of an earlier command to show the most recent match; Ctrl+R again finds an older one. Enter runs the match, End keeps it on the line for editing, Esc cancels. |
| Left / Right | Moves the cursor one character. |
| Home / End, Ctrl+A / Ctrl+E | Moves to the beginning / end of the line. |
| Backspace / Delete | Deletes the character before / at the cursor. |
| Ctrl+U | Deletes from the cursor back to the beginning of the line. |
| Ctrl+K | Deletes from the cursor to the end of the line. |
| Ctrl+W, Alt+Backspace | Deletes the word before the cursor. |
| Ctrl+L | Clears the screen and keeps what you have typed. |
| Ctrl+C | While a command is running: stops it and keeps the connection open (see [Ctrl+C during a command](#ctrl-c-during-a-command)). While typing: abandons the current line only; earlier lines of an unfinished statement stay pending (Esc abandons them too). Ctrl+C is never a way to end BroadSQL: use `exit`. |
| Ctrl+D | Deletes the character at the cursor. On an empty line, ends BroadSQL the same way `exit` does. |

These depend on what your terminal sends, so they are not guaranteed:

- Ctrl+Left / Ctrl+Right move to the previous / next word in the Windows console and in most terminals.
- Ctrl+Delete deletes the next word in terminals that send a distinct code for it. The Windows console
  sends the same code as Delete, so there it deletes one character.
- Ctrl+Backspace and Shift+Backspace reach BroadSQL as a plain Backspace in the Windows console and delete one
  character. Use Ctrl+W or Alt+Backspace to delete a word.
- Other Alt+letter editing commands (for example Alt+B / Alt+F to move by word) may work, depending on the
  terminal. A terminal sends Alt+key as Esc followed by the key, so pressing Esc and another key less than
  about a tenth of a second apart can be read as Alt+that key.

## Entering commands

Every command, BroadSQL's own or plain SQL, ends with `;`. A command can span several lines: it runs once
BroadSQL reads the closing `;`. Several commands can be typed on one line, each ending with its own `;`;
they run in order, and if one fails, the ones after it on that line are not run. A `;` inside a quoted
value (`SELECT 'a;b';`) is never taken as the end of a command.

The prompt shows the current connection, for example `DEVDB> `. During an API session it also shows the
API and its environment: `DEVDB [API crm:Staging]> ` (see [Universal API Client](universal_api_client.md)).

Shortcuts typed on a line by themselves:

| Line | Effect |
|---|---|
| `/` | Runs the last `SELECT`/`INSERT`/`UPDATE`/`DELETE` again, on the current connection. |
| `/ <environment>` | Runs it on the connection of another Environment of the same Database Group (see [Patterns](patterns.md)). |
| `//` | Shows the last query on one line, without running it (same as `SHOW QUERY`). |
| `exit` | Ends BroadSQL, closing every connection and the terminal cleanly. It is recognized in lowercase only and needs no `;`. |

To run the last query again and again, for monitoring, use [`REPEAT EVERY 10s;`](commands/repeat.md) (or
`REPEAT / EVERY 10s;`): it repeats the last query until Ctrl+C. A `REPEAT BEGIN ... END` block can be typed over
several lines: its inner `;` do not run it, it runs when its `END EVERY ...;` line is entered.

## Ctrl+C during a command

Ctrl+C stops what is running and brings back the prompt, on the same connection:

| When | What Ctrl+C does |
|---|---|
| At the prompt | Abandons the line being typed. Nothing else happens. |
| During a query | BroadSQL asks the database to cancel the running statement (JDBC `Statement.cancel()`) and waits until the database driver gives control back. A query not yet sent is not sent. While rows are being displayed, the display stops. The rest of the typed line is skipped. The same applies to `/` and `/ <environment>`. |
| During a Script | The running statement is cancelled as above and the whole run stops: no further statement runs, at any nesting level, whatever `ON ERROR` says. The status is `CANCELLED` (see [Cancelling a Script](scripting_run_lifecycle.md#cancelling-a-script)). This applies to `@script`, `LIB RUN` and a Run from the Editor (press Ctrl+C in the console window). |
| During a [`REPEAT`](commands/repeat.md) | The running statement is cancelled as above, or the wait is ended, and the `REPEAT` stops. |

**Cancellation depends on the database.** BroadSQL can only ask; the database driver decides how fast the
statement stops, and some never stop it early. In BroadSQL's own tests: H2 and SQLite stop at once; HSQLDB
stops when Autocommit is off but finishes the statement when Autocommit is on; Derby does not support
cancellation and always finishes the statement. The PostgreSQL driver sends the cancellation to the server
over a separate connection; the Oracle, MySQL and MariaDB drivers are also designed to send it to the server.
The server then stops the statement at its next check, which is not always immediate. While the driver has not given
control back, BroadSQL waits: pressing Ctrl+C again asks again, and BroadSQL never closes the connection or
ends itself to stop a statement.

**Pending changes.** When the database reports the cancelled statement as an error, it is handled like any SQL
error: with Autocommit off, BroadSQL rolls back the changes pending since the last `COMMIT` and says so with
`Pending changes since the last COMMIT on ... were rolled back.` (on PostgreSQL the transaction cannot continue
after an error anyway). When BroadSQL stops on its own side, before the statement was sent or while its rows
were displayed, the database reported no error and pending changes are kept. Cancelling a `SELECT` never undoes
work already committed.

## History

Up/Down and Ctrl+R recall commands from the current session and from earlier ones: history is saved per
operating-system user and reloaded at the next start. By default it is stored in
`%USERPROFILE%\.broadsql\history` (Windows) or `$HOME/.broadsql/history` (Linux, macOS); the
[`jlinehistoryfile`](settings.md) setting changes the location. What is stored is the line exactly as you
typed it. A password you type is never echoed and never recorded.

## TAB completion

TAB completes BroadSQL commands, SQL keywords and, once connected, database object names, including in a
statement that spans several lines:

```text
sel<TAB>                    -> SELECT
show end<TAB>                -> SHOW ENDPOINT (first of two matches, see below)
SELECT * FROM c<TAB>         -> completes directly if only one table/view starts with "c"
```

One matching candidate completes directly. Several matching candidates open a selection menu instead
of BroadSQL picking one for you: TAB again moves to the next candidate, Shift+Tab moves back to the
previous one, and typing more characters accepts whichever candidate is currently highlighted and
keeps editing the line normally. Pressing Enter while the menu is showing accepts the highlighted
candidate first (without running the line); press Enter again to actually run it. Table, view, schema
and column completion needs an active database connection; BroadSQL command and SQL keyword
completion work either way.

TAB completes only when you are typing a word:

- With only spaces and TABs between the start of the line and the cursor, TAB inserts a TAB, so you can
  indent a continuation line (`<TAB>AND STATUS = 1`).
- After a space (`DUMP <TAB>`), TAB lists the candidates and leaves the line unchanged; type the first
  letters, then TAB, to complete one of them.
- Pasted text is inserted as it was copied: its TABs stay TABs and no completion runs. A terminal that
  marks pasted text keeps the whole paste on the input line until you press Enter. The Windows console
  sends a paste like typing, so each pasted line is submitted in turn; a TAB at the start of a line or
  after a space still adds nothing. A TAB pasted directly after a word, with no space before it, is
  taken as a completion request there, so prefer spaces or a line break before a TAB in SQL you paste
  into the Windows console.

### Names BroadSQL knows

Wherever a command expects the name of something BroadSQL stores, TAB offers those names:

```text
CONNECT W<TAB>                -> the connection IDs that start with W
ENV Q<TAB>                    -> the Environments of the current Database Group that start with Q
EDIT ENVIRONMENT P<TAB>       -> the Environments that start with P
EDIT GROUP S<TAB>             -> the Database Groups that start with S
REACTIVATE CONNECTION O<TAB>  -> the inactive connection IDs that start with O
LIB EDIT QR<TAB>              -> the Scripts Library scripts that start with QR (LIB RUN, SHOW, DEL, LINT too)
@QR<TAB>                      -> the same scripts, for running
LIB RESTORE <TAB>             -> the archived Scripts (their original paths)
DESCR c<TAB>                  -> the tables and views that start with c
SHOW PK sales.o<TAB>          -> the tables and views of schema sales that start with o
DUMP <TAB>                    -> /, LIB and table names; after a source: TO and AS; after AS: the formats
DUMP @s<TAB>                  -> the Scripts Library scripts that start with s, as after DUMP LIB
```

The connection commands (`SHOW CONNECTION`, `PING`, `EDIT CONNECTION`, `DEL CONNECTION`,
`DUPLICATE CONNECTION`), `SHOW GROUP` and `DEL ENVIRONMENT`/`DEL GROUP` complete their arguments the same way.

Table and view names are completed wherever a command expects a table of the current connection: `DESCR`,
`SHOW PK`, `SHOW FK`, `SHOW REFERENCES`, `SHOW INDEXES`, `DUMP` (and `PULL`), `LOAD`, `ALL`, `CNT` and the
table of `COMPARE TABLE STRUCTURE`, including their short forms. Type a schema and a dot first
(`sales.o<TAB>`) to complete the tables of that schema; the schema is kept as you typed it. Positions that take
a pattern or a text (`SHOW TABLES`, `FIND COLUMN`, `FIND FK`, `FIND INDEX`), a file, or a table of another
connection (`LINK TABLE`) are not completed with table names.

Matching is by prefix only and ignores case; the name inserted is always the one BroadSQL has stored, so
`CONNECT war<TAB>` can become `CONNECT WAREHOUSE_DEV`. Nothing is guessed: if no name matches, the line is left
as you typed it. The candidates are read from the same places the commands themselves use, so a connection,
Environment, Database Group, API or Script you have just created or deleted is reflected immediately. A name
that contains a space is inserted in double quotes, the way BroadSQL expects it; if you typed the opening quote
yourself, completion keeps it. Connections, Environments and Database Groups are the active ones (except for
`REACTIVATE CONNECTION`); Scripts are listed as paths relative to the Scripts Library, without its archived
entries (except for `LIB RESTORE`).

### APIs

```text
CONNECT API C<TAB>            -> the API IDs that start with C
SHOW API ENVIRONMENTS <TAB>   -> the API IDs
RUN get<TAB>                  -> GET, and the endpoints of the active API whose name, alias or ID starts with
                                 get, each expanded to its URL
RUN /api/customer/:id?<TAB>   -> the endpoint's query parameter names, then their allowed values after =
SYNTAX <TAB>                  -> the aliases of the active API's endpoints
SHOW ENDPOINT <TAB>           -> the endpoints of the active API, by alias (by name when there is no alias)
${ENV:CUS<TAB>                -> operating-system variable names (never their values)
```

See [Universal API Client](universal_api_client.md) for the API session these depend on.

### Files and folders

Where a command expects a file, TAB completes file and folder names from the disk:

- `@` followed by an absolute path or by `./` (a bare `@name` completes Scripts Library scripts instead);
- `IMPORT API BRUNO <file>` and `LOAD <table> <file>`;
- `<@file>`, `<@csv:file...>` and `<@excel:file...>` anywhere in a statement.

For `LOAD`, a name without a folder completes from the export folder ([`DefaultFolder`](settings.md)), where
`LOAD` looks for it. Paths are completed the way you type them: `C:\data\`, `./data/` and `/home/me/data/`
keep their own separators and a backslash is never doubled. A folder ends with its separator so the next TAB
continues inside it. A path containing a space is inserted in double quotes, except inside `<@...>`, which has
no quoting (there, completion stops at the space). The `TO` destination of `DUMP` is a name in the export
folder, not a path, so it is not completed.

## Colors, themes and highlighting

When the terminal supports colors, BroadSQL:

- highlights what you type: SQL keywords, strings and numbers, comments, and BroadSQL commands with their
  arguments;
- shows errors, warnings, information messages and completed operations in their own colors;
- colors the prompt, with a distinct style when the current connection's Environment is flagged Production.

Highlighting is only for reading: what runs is exactly what you typed, and unknown or vendor-specific SQL is
simply left uncolored. Two settings control this:

| Setting | Values |
|---|---|
| [`color`](settings.md) | `AUTO` (default): colors only when the terminal reports color support, never when output is redirected to a file or a pipe. `ON`: always. `OFF`: never. |
| [`theme`](settings.md) | `default-dark` (default), `default-light` (light backgrounds), `classic`, `high-contrast-dark`, `high-contrast-light`, `mono` (bold and underline only, no colors), `none`. |

`theme=none` keeps the appearance of earlier releases: no highlighting, plain prompt and messages. Colors
never reach the [command activity log](logging.md) or an export.

## Table layout and window width

Every table BroadSQL prints, query results and command output alike, has the same shape: a border line, the
header, a border line, the rows, and a closing border line. Every line starts and ends with `|`, which also
separates the columns, and border lines are drawn with `-`:

```text
|--|-----|
|ID|NAME |
|--|-----|
|1 |Alice|
|2 |Bob  |
|--|-----|
```

Result tables follow the width of the terminal window ([`displaymode`](settings.md), `AUTO` by default).
This applies to query results and to the tables of commands such as `SHOW TABLES`, `SHOW FK` or
`LIB LIST`:

| Mode | Layout |
|---|---|
| `NORMAL` | Each column as wide as its header and declared size; values never shortened. `AUTO` uses it from 80 to 159 columns, and whenever the width is unknown. |
| `COMPACT` | Fits the table to the window: the widest columns are narrowed and long values shortened on screen, marked with `~`. When even that cannot fit, each row is shown vertically, one `COLUMN: value` line per column. `AUTO` uses it below 80 columns. |
| `WIDE` | `NORMAL` widths with extra spacing around the column separators. `AUTO` uses it from 160 columns. |

The width is read before each table, so after resizing the window the next result uses the new width. Only the
display changes: `<@last:...>`, `DUMP /` and every export always get the full values.

## The basic console (`activatejline=OFF`)

Setting [`activatejline`](settings.md) to `OFF` replaces the standard console with the operating system's basic
console: there is no history recall, no TAB completion, none of the shortcuts above except what the operating
system itself provides, no colors under `color=AUTO`, and tables use the `NORMAL` layout. If the standard console
cannot start, BroadSQL prints one warning and uses the basic console. Every command works the same either way.

## Related pages

This page covers typing and reading. What the commands themselves do is described in their own sections:

- [Connections, Database Groups and Environments](connections.md): the prompt, `/ <environment>` and `ENV`.
- [Patterns & List Sources](patterns.md): `/`, `/ <environment>` and the list sources usable inside a query.
- [Running Scripts](scripting_running.md) and [Nested Scripts and run lifecycle](scripting_run_lifecycle.md): what
  `@script` runs, and what Ctrl+C does to a Script.
- [Exporting data](export.md): `DUMP /` and the other ways to save a result.
- [Application settings](settings.md): `activatejline`, `color`, `theme`, `displaymode`, `jlinehistoryfile`.
