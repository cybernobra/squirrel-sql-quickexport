/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 cybernobra (https://github.com/cybernobra)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. See the LICENSE file.
 */
package be.squirrel_sql.plugin;

import java.awt.Component;
import java.awt.Container;
import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import net.sourceforge.squirrel_sql.client.IApplication;
import net.sourceforge.squirrel_sql.client.session.SQLExecutionInfo;
import net.sourceforge.squirrel_sql.client.session.mainpanel.IResultTab;
import net.sourceforge.squirrel_sql.fw.datasetviewer.DataSetViewerTablePanel;
import net.sourceforge.squirrel_sql.fw.gui.TablePopupMenu;

/**
 * Adds the quick export entry to the result table right-click menu, right below
 * "Export CSV / MS Excel / XML / JSON...".
 * <p>
 * SquirrelSQL 5.0.0 offers no extension point for this menu ({@code TablePopupMenu} is hard-coded by
 * {@code TablePopupMenuHandler}, with no registry nor listener). So we listen to the Swing
 * {@link MenuSelectionManager}, which reports the opening of any popup menu: on the first opening of a
 * {@link TablePopupMenu}, the entry is inserted (the menu is then reused, one per result). Only public API is used.
 */
final class ResultPopupMenu implements ChangeListener {

   private static final String MARKER = ResultPopupMenu.class.getName();

   private final IApplication _app;
   private final String _label;

   /**
    * @param label entry label, the same as the Session menu action
    */
   ResultPopupMenu(IApplication app, String label) {
      _app = app;
      _label = label;
   }

   @Override
   public void stateChanged(ChangeEvent e) {
      MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
      if (path.length > 0 && path[0] instanceof TablePopupMenu popup && !containsEntry(popup)) {
         addEntry(popup);
      }
   }

   private static boolean containsEntry(JPopupMenu popup) {
      for (Component c : popup.getComponents()) {
         if (c instanceof JMenuItem item && null != item.getClientProperty(MARKER)) {
            return true;
         }
      }
      return false;
   }

   private void addEntry(JPopupMenu popup) {
      JMenuItem item = new JMenuItem(_label);
      item.putClientProperty(MARKER, Boolean.TRUE);
      // The invoker of a TablePopupMenu is the result table
      item.addActionListener(evt -> {
         JTable table = (JTable) popup.getInvoker();
         QuickExportAction.export(_app, table, findSqlExecution(table));
      });
      popup.insert(item, indexOfNativeExport(popup) + 1);

      // The menu may already be on screen: resize it to show the new entry
      SwingUtilities.invokeLater(() -> {
         if (popup.isVisible()) {
            popup.pack();
         }
      });
   }

   /**
    * @return position of the native entry "Export CSV / MS Excel / XML / JSON..." (action
    * {@code TablePopupMenu$ExportAction}), or the last position if not found
    */
   private static int indexOfNativeExport(JPopupMenu popup) {
      Component[] components = popup.getComponents();
      for (int i = 0; i < components.length; i++) {
         if (components[i] instanceof JMenuItem item) {
            Action action = item.getAction();
            if (null != action && action.getClass().getName().endsWith("TablePopupMenu$ExportAction")) {
               return i;
            }
         }
      }
      return components.length - 1;
   }

   /**
    * Walks up from the clicked table to the SQL result tab containing it.
    *
    * @return SQL execution of the result, null if the table is not a SQL result (e.g. Content tab of the object tree)
    */
   private static SQLExecutionInfo findSqlExecution(JTable table) {
      for (Container c = table.getParent(); null != c; c = c.getParent()) {
         if (c instanceof IResultTab resultTab
               && resultTab.getSQLResultDataSetViewer() instanceof DataSetViewerTablePanel tablePanel
               && tablePanel.getTable() == table) {
            return tablePanel.getDataModelImplementationDetails().getSQLExecutionInfo();
         }
      }
      return null;
   }
}
