/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 cybernobra (https://github.com/cybernobra)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. See the LICENSE file.
 */
package be.squirrel_sql.plugin;

import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.JFileChooser;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import net.sourceforge.squirrel_sql.client.IApplication;
import net.sourceforge.squirrel_sql.client.action.SquirrelAction;
import net.sourceforge.squirrel_sql.client.session.ISQLPanelAPI;
import net.sourceforge.squirrel_sql.client.session.SQLExecutionInfo;
import net.sourceforge.squirrel_sql.client.session.action.ISQLPanelAction;
import net.sourceforge.squirrel_sql.client.session.mainpanel.IResultTab;
import net.sourceforge.squirrel_sql.fw.datasetviewer.DataSetViewerTablePanel;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.ExportDataInfoList;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.ExportFileWriter;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.FileEndings;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.JTableExportData;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.TableExportPreferences;
import net.sourceforge.squirrel_sql.fw.gui.action.fileexport.TableExportPreferencesDAO;
import net.sourceforge.squirrel_sql.fw.resources.IResources;
import net.sourceforge.squirrel_sql.fw.util.log.ILogger;
import net.sourceforge.squirrel_sql.fw.util.log.LoggerController;

/**
 * Quick export of a SQL result, without any dialog.
 * <p>
 * As an action (Session menu, Ctrl+Shift+E) it exports the selected result tab of the active SQL panel;
 * the result table right-click goes through {@link ResultPopupMenu}, which calls {@link #export}.
 * <ul>
 *    <li>Settings (format, headers, font, SQL sheet, separator...): those of the native export dialog,
 *    reloaded each time.</li>
 *    <li>Directory: the one of the last native export. If there is none yet, a directory chooser is shown once
 *    and its choice is stored in the native preferences.</li>
 *    <li>File name: {@code SCHEMA_TABLE_yyyyMMdd_HHmmss}, see {@link #buildFileName}; the date is the query
 *    execution time; extension according to the format.</li>
 *    <li>Existing file: overwritten (same result = same name; re-executed query = new date).</li>
 *    <li>Always the complete table, never the selection; never the post-export command.</li>
 * </ul>
 */
public class QuickExportAction extends SquirrelAction implements ISQLPanelAction {

   private static final ILogger s_log = LoggerController.createLogger(QuickExportAction.class);

   private static final int MAX_TABLES_PART_LENGTH = 120;
   private static final String DEFAULT_NAME = "SQLRESULT";
   private static final String TRUNCATED_SUFFIX = "_ETC";

   /** Words that may follow a table and therefore cannot be its alias. */
   private static final Set<String> KEYWORDS_AFTER_TABLE = Set.of(
         "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "NATURAL",
         "ON", "USING", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT", "MINUS",
         "FETCH", "LIMIT", "OFFSET", "FOR", "WITH", "OPTIMIZE", "WINDOW", "QUALIFY", "SELECT", "INTO");

   private transient ISQLPanelAPI _sqlPanel;

   public QuickExportAction(IApplication app, IResources resources) {
      super(app, resources);
      // JMenu.add(Action) uses ACCELERATOR_KEY: the shortcut then works anywhere in the main window
      putValue(ACCELERATOR_KEY, getKeyStroke());
      setEnabled(false);
   }

   @Override
   public void setSQLPanel(ISQLPanelAPI panel) {
      _sqlPanel = panel;
      setEnabled(null != panel);
   }

   @Override
   public void actionPerformed(ActionEvent evt) {
      IResultTab resultTab = _sqlPanel.getSQLResultExecuter().getSelectedResultTab();
      if (null == resultTab) {
         _app.getMessageHandler().showWarningMessage("Quick export: no result tab selected.");
         return;
      }
      if (!(resultTab.getSQLResultDataSetViewer() instanceof DataSetViewerTablePanel tablePanel)) {
         _app.getMessageHandler().showWarningMessage("Quick export: the result must be displayed as a table (not as text).");
         return;
      }
      export(_app, tablePanel.getTable(), tablePanel.getDataModelImplementationDetails().getSQLExecutionInfo());
   }

   /**
    * Exports the complete table with the native export settings, into the native export directory.
    *
    * @param table displayed result table
    * @param info  SQL execution of the result (null: name {@code SQLRESULT}, current date)
    */
   static void export(IApplication app, JTable table, SQLExecutionInfo info) {
      Window window = SwingUtilities.getWindowAncestor(table);
      TableExportPreferences prefs = TableExportPreferencesDAO.loadPreferences();

      File directory = findExportDirectory(prefs, window);
      if (null == directory) {
         return;
      }

      String sql = null == info ? null : info.getQueryHolder().getOriginalQuery();
      Date executionTime = null == info ? null : info.getSQLExecutionStartTime();
      File file = new File(directory, buildFileName(sql, executionTime) + "." + FileEndings.getByTableExportPreferences(prefs));

      // In-memory preferences only: the native dialog keeps its own file
      prefs.setFile(file.getAbsolutePath());

      long rows;
      try {
         rows = ExportFileWriter.export(ExportDataInfoList.single(new JTableExportData(table, info, true)), prefs, null, window);
      } catch (RuntimeException e) {
         // ExportFileWriter already displayed the error (file open in Excel, disk...)
         s_log.error("Quick export failed: " + file, e);
         return;
      }

      app.getMessageHandler().showMessage("Quick export of " + NumberFormat.getIntegerInstance().format(rows)
            + " rows into " + file.getAbsolutePath());
   }

   /**
    * @return directory of the last native export; otherwise the one chosen by the user (then stored in the native
    * preferences); null if cancelled
    */
   private static File findExportDirectory(TableExportPreferences prefs, Window window) {
      String lastFile = prefs.getFile();
      if (null != lastFile && !lastFile.isBlank()) {
         File file = new File(lastFile.trim());
         return file.isDirectory() ? file : file.getParentFile();
      }

      JFileChooser chooser = new JFileChooser();
      chooser.setDialogTitle("Quick export: export directory");
      chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      if (JFileChooser.APPROVE_OPTION != chooser.showDialog(window, "Choose")) {
         return null;
      }

      File directory = chooser.getSelectedFile();
      prefs.setFile(new File(directory, DEFAULT_NAME + "." + FileEndings.getByTableExportPreferences(prefs)).getAbsolutePath());
      TableExportPreferencesDAO.savePreferences(prefs);
      return directory;
   }

   /**
    * Builds the export file name (without extension) from the SQL of the result: {@code SCHEMA_TABLE_yyyyMMdd_HHmmss}.
    * <ul>
    *    <li>Tables read after {@code FROM} / {@code JOIN} (and lists {@code FROM A, B}), subqueries included,
    *    in order of appearance, without duplicates.</li>
    *    <li>CTE ({@code WITH all_data AS (...) SELECT ... FROM all_data}): the tables of the CTE body are ignored,
    *    the name of the CTE read by the main query names the file ({@code ALL_DATA}).</li>
    *    <li>Same for a named derived table ({@code FROM (SELECT ... UNION ALL ...) AS UnifiedData} →
    *    {@code UNIFIEDDATA}): always the alias, never the tables of the subquery.</li>
    *    <li>Qualification: {@code SCHEMA.TABLE} and the AS400 system syntax {@code SCHEMA/TABLE}.</li>
    *    <li>Tables grouped by schema (order of first appearance), schema written once:
    *    {@code S1_T1_T2_S2_T3}, then the unqualified tables.</li>
    *    <li>No table found: {@code SQLRESULT}.</li>
    *    <li>Tables part limited to {@value #MAX_TABLES_PART_LENGTH} characters, suffix {@code _ETC} when cut.</li>
    * </ul>
    * No full SQL parser (jsqlparser does not know {@code SCHEMA/TABLE}): a small lexer is enough, the worst case
    * being a less meaningful name, never a failed export.
    *
    * @param sql           SQL of the exported result (may be null)
    * @param executionTime query execution time (null: now)
    * @return Windows-safe file name without extension
    */
   static String buildFileName(String sql, Date executionTime) {
      List<SqlTable> tables = null == sql || sql.isBlank() ? List.of() : extractTables(tokenize(sql));
      String date = new SimpleDateFormat("yyyyMMdd_HHmmss").format(null == executionTime ? new Date() : executionTime);
      return buildTablesPart(tables) + "_" + date;
   }

   private static String buildTablesPart(List<SqlTable> tables) {
      if (tables.isEmpty()) {
         return DEFAULT_NAME;
      }

      // Grouped by schema (order of first appearance): each schema is written only once
      Map<String, List<String>> tablesBySchema = new LinkedHashMap<>();
      tables.forEach(t -> tablesBySchema.computeIfAbsent(t.schema(), s -> new ArrayList<>()).add(t.table()));

      List<String> parts = new ArrayList<>();
      tablesBySchema.forEach((schema, schemaTables) -> {
         if (null != schema) {
            parts.add(schema);
         }
         parts.addAll(schemaTables);
      });

      StringBuilder name = new StringBuilder();
      for (String part : parts) {
         // Characters outside [A-Za-z0-9_$#@-] are forbidden or dubious in Windows file names
         String safePart = part.replaceAll("[^A-Za-z0-9_$#@\\-]", "_");
         if (!name.isEmpty() && name.length() + 1 + safePart.length() > MAX_TABLES_PART_LENGTH - TRUNCATED_SUFFIX.length()) {
            return name + TRUNCATED_SUFFIX;
         }
         name.append(name.isEmpty() ? "" : "_").append(safePart);
      }
      return name.length() > MAX_TABLES_PART_LENGTH
            ? name.substring(0, MAX_TABLES_PART_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX
            : name.toString();
   }

   /**
    * @return tables (or CTE / derived table aliases) read by the SQL, in order of appearance, without duplicates
    */
   private static List<SqlTable> extractTables(List<String> tokens) {
      boolean[] inCteBody = markCteBodies(tokens);
      Set<SqlTable> tables = new LinkedHashSet<>();

      // Stack of parenthesis levels: true if the level is a (sub)query, false if a function call or a list of
      // values. Avoids taking EXTRACT(YEAR FROM col) or TRIM(x FROM col) for a table.
      Deque<Boolean> levelIsQuery = new ArrayDeque<>();
      levelIsQuery.push(true);

      for (int i = 0; i < tokens.size(); i++) {
         if (inCteBody[i]) {
            // CTE body: balanced parentheses, the stack is not affected
            continue;
         }
         String token = tokens.get(i).toUpperCase(Locale.ROOT);
         if ("(".equals(token)) {
            levelIsQuery.push(startsSubquery(tokens, i));
         } else if (")".equals(token)) {
            if (levelIsQuery.size() > 1) {
               levelIsQuery.pop();
            }
         } else if (levelIsQuery.peek() && ("FROM".equals(token) || "JOIN".equals(token))) {
            boolean listAllowed = "FROM".equals(token);
            int aliasIndex = readNamedDerivedTable(tokens, i + 1, tables);
            if (aliasIndex < 0) {
               readTableList(tokens, i + 1, listAllowed, tables);
            } else {
               if (listAllowed && aliasIndex + 1 < tokens.size() && ",".equals(tokens.get(aliasIndex + 1))) {
                  readTableList(tokens, aliasIndex + 2, true, tables);
               }
               // Subquery skipped entirely (balanced parentheses): its tables do not name the file
               i = aliasIndex;
            }
         }
      }

      return new ArrayList<>(tables);
   }

   /**
    * Named derived table: {@code FROM (SELECT ... UNION ALL SELECT ...) AS UnifiedData}. The alias always names the
    * file ({@code UNIFIEDDATA}), never the tables of the subquery: up to the user to choose a meaningful alias.
    *
    * @return index of the alias token if the alias was used, -1 otherwise
    */
   private static int readNamedDerivedTable(List<String> tokens, int start, Set<SqlTable> tables) {
      if (!startsSubquery(tokens, start)) {
         return -1;
      }
      int end = indexOfClosingParenthesis(tokens, start);
      if (end < 0) {
         return -1;
      }

      int aliasIndex = end + 1;
      if (aliasIndex < tokens.size() && "AS".equalsIgnoreCase(tokens.get(aliasIndex))) {
         aliasIndex++;
      }
      if (aliasIndex >= tokens.size() || !isAlias(tokens.get(aliasIndex))) {
         return -1;
      }

      tables.add(new SqlTable(null, unquote(tokens.get(aliasIndex)).toUpperCase(Locale.ROOT)));
      return aliasIndex;
   }

   /**
    * Reads the table(s) following a FROM / JOIN, starting at {@code start}.
    * A subquery {@code (SELECT ...)} is left to the main loop.
    */
   private static void readTableList(List<String> tokens, int start, boolean listAllowed, Set<SqlTable> tables) {
      int i = start;
      while (i < tokens.size()) {
         if ("LATERAL".equalsIgnoreCase(tokens.get(i))) {
            i++;
         }
         if (i >= tokens.size() || !isIdentifier(tokens.get(i))) {
            return;
         }

         // Qualified name: ident ( (. | /) ident )*
         List<String> nameParts = new ArrayList<>();
         nameParts.add(unquote(tokens.get(i++)));
         while (i + 1 < tokens.size() && (".".equals(tokens.get(i)) || "/".equals(tokens.get(i)))
               && isIdentifier(tokens.get(i + 1))) {
            nameParts.add(unquote(tokens.get(i + 1)));
            i += 2;
         }

         // Table function (TABLE(...), UNNEST(...)): not a table
         if (i < tokens.size() && "(".equals(tokens.get(i))) {
            return;
         }

         String table = nameParts.get(nameParts.size() - 1).toUpperCase(Locale.ROOT);
         String schema = nameParts.size() > 1 ? nameParts.get(nameParts.size() - 2).toUpperCase(Locale.ROOT) : null;
         tables.add(new SqlTable(schema, table));

         // Optional alias: [AS] alias
         if (i < tokens.size() && "AS".equalsIgnoreCase(tokens.get(i))) {
            i++;
         }
         if (i < tokens.size() && isAlias(tokens.get(i))) {
            i++;
         }

         if (!listAllowed || i >= tokens.size() || !",".equals(tokens.get(i))) {
            return;
         }
         i++;
      }
   }

   /**
    * Marks the tokens of the bodies of the CTE declared by {@code WITH name AS (...)} or {@code , name AS (...)},
    * parentheses included: their tables do not name the file, the name of the CTE read by the main query does.
    */
   private static boolean[] markCteBodies(List<String> tokens) {
      boolean[] inBody = new boolean[tokens.size()];
      for (int i = 1; i + 2 < tokens.size(); i++) {
         String previous = tokens.get(i - 1).toUpperCase(Locale.ROOT);
         if (("WITH".equals(previous) || "RECURSIVE".equals(previous) || ",".equals(previous))
               && !inBody[i]
               && isIdentifier(tokens.get(i))
               && "AS".equalsIgnoreCase(tokens.get(i + 1))
               && "(".equals(tokens.get(i + 2))) {
            int end = indexOfClosingParenthesis(tokens, i + 2);
            for (int j = i + 2; j <= (end < 0 ? tokens.size() - 1 : end); j++) {
               inBody[j] = true;
            }
         }
      }
      return inBody;
   }

   /** @return true if {@code tokens[open]} is "(" opening a (sub)query: followed by SELECT, WITH or another "(". */
   private static boolean startsSubquery(List<String> tokens, int open) {
      if (open + 1 >= tokens.size() || !"(".equals(tokens.get(open))) {
         return false;
      }
      String next = tokens.get(open + 1).toUpperCase(Locale.ROOT);
      return "SELECT".equals(next) || "WITH".equals(next) || "(".equals(next);
   }

   /** @return index of the ")" matching the "(" at {@code open}, -1 if unbalanced. */
   private static int indexOfClosingParenthesis(List<String> tokens, int open) {
      int depth = 0;
      for (int j = open; j < tokens.size(); j++) {
         if ("(".equals(tokens.get(j))) {
            depth++;
         } else if (")".equals(tokens.get(j)) && --depth == 0) {
            return j;
         }
      }
      return -1;
   }

   /**
    * Minimal lexer: identifiers / words ({@code [A-Za-z0-9_$#@]+}), double-quoted identifiers and one-character
    * symbols. Comments and string literals are skipped.
    */
   private static List<String> tokenize(String sql) {
      List<String> tokens = new ArrayList<>();
      int n = sql.length();
      int i = 0;
      while (i < n) {
         char c = sql.charAt(i);
         if (Character.isWhitespace(c)) {
            i++;
         } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
            while (i < n && sql.charAt(i) != '\n') {
               i++;
            }
         } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
            int end = sql.indexOf("*/", i + 2);
            i = end < 0 ? n : end + 2;
         } else if (c == '\'') {
            // String literal, '' being an escaped quote
            i++;
            while (i < n && (sql.charAt(i) != '\'' || (i + 1 < n && sql.charAt(i + 1) == '\''))) {
               i += sql.charAt(i) == '\'' ? 2 : 1;
            }
            i++;
         } else if (c == '"') {
            int end = sql.indexOf('"', i + 1);
            end = end < 0 ? n - 1 : end;
            tokens.add(sql.substring(i, end + 1));
            i = end + 1;
         } else if (isWordChar(c)) {
            int start = i;
            while (i < n && isWordChar(sql.charAt(i))) {
               i++;
            }
            tokens.add(sql.substring(start, i));
         } else {
            tokens.add(String.valueOf(c));
            i++;
         }
      }
      return tokens;
   }

   private static boolean isWordChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
   }

   private static boolean isIdentifier(String token) {
      return token.startsWith("\"") || (!token.isEmpty() && isWordChar(token.charAt(0)));
   }

   private static boolean isAlias(String token) {
      return isIdentifier(token) && !KEYWORDS_AFTER_TABLE.contains(token.toUpperCase(Locale.ROOT));
   }

   private static String unquote(String token) {
      return token.startsWith("\"") ? token.replace("\"", "") : token;
   }

   /** Table read by the SQL; {@code schema} is null when unqualified. */
   private record SqlTable(String schema, String table) {
   }
}
