/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 cybernobra (https://github.com/cybernobra)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. See the LICENSE file.
 */
package be.squirrel_sql.plugin;

import javax.swing.Action;
import javax.swing.MenuSelectionManager;
import net.sourceforge.squirrel_sql.client.IApplication;
import net.sourceforge.squirrel_sql.client.plugin.DefaultSessionPlugin;
import net.sourceforge.squirrel_sql.client.plugin.PluginException;
import net.sourceforge.squirrel_sql.client.plugin.PluginResources;
import net.sourceforge.squirrel_sql.client.plugin.PluginSessionCallback;
import net.sourceforge.squirrel_sql.client.plugin.PluginSessionCallbackAdaptor;
import net.sourceforge.squirrel_sql.client.session.ISession;

/**
 * SquirrelSQL "Quick export" plugin:
 * <ul>
 *    <li>right-click on a result: entry below "Export CSV / MS Excel / XML / JSON..." ({@link ResultPopupMenu});</li>
 *    <li>Session menu: action {@link QuickExportAction} (shortcut Ctrl+Shift+E, can be changed through the
 *    SquirrelSQL shortcuts).</li>
 * </ul>
 * Deployment: copy {@code quickexport.jar} into {@code <SquirrelSQL>/plugins/} and restart SquirrelSQL.
 */
public class QuickExportPlugin extends DefaultSessionPlugin {

   private ResultPopupMenu _resultPopupMenu;

   @Override
   public String getInternalName() {
      return "quickexport";
   }

   @Override
   public String getDescriptiveName() {
      return "Quick export";
   }

   @Override
   public String getVersion() {
      return "1.0.0";
   }

   @Override
   public String getAuthor() {
      return "cybernobra";
   }

   @Override
   public synchronized void initialize() throws PluginException {
      super.initialize();
      IApplication app = getApplication();

      QuickExportAction action = new QuickExportAction(app, new PluginResources(getClass().getPackageName() + ".quickexport", this));
      app.getActionCollection().add(action);
      app.addToMenu(IApplication.IMenuIDs.SESSION_MENU, action);

      _resultPopupMenu = new ResultPopupMenu(app, (String) action.getValue(Action.NAME));
      MenuSelectionManager.defaultManager().addChangeListener(_resultPopupMenu);
   }

   @Override
   public void unload() {
      MenuSelectionManager.defaultManager().removeChangeListener(_resultPopupMenu);
      super.unload();
   }

   @Override
   public PluginSessionCallback sessionStarted(ISession session) {
      return new PluginSessionCallbackAdaptor();
   }
}
