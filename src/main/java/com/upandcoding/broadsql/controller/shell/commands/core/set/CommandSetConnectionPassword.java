/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.HashMap;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Changes the password of the current database connection (not the CDF): {@code SET PASSWORD}, no
 * arguments - the command runs as an interactive, multi-step prompt.
 *
 * <p>Refuses to run against the {@code $CDF} connection itself (use {@code SET MASTER PASSWORD}
 * instead) and refuses if the current connection has uncommitted changes (commit or roll back first).
 * Otherwise it asks for confirmation, re-authenticates with the CDF master password, then prompts for
 * the new connection password twice for verification.
 *
 * <p>On confirmation, the new password is written to the CDF entry for this connection and the
 * password is changed on the remote database itself; if either step fails, the CDF entry is reverted
 * to the previous password rather than left out of sync with the database.
 */
public class CommandSetConnectionPassword extends Command {

	static final HashMap<String, Character> names = new HashMap<String, Character>();

	public CommandSetConnectionPassword() {
		super("SET PASSWORD", "SE PA");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		if (this.platform.toUpperCase().equals(SpringPropertiesConfig.CDF_ID)) {
			console.warn("You must use command SET MASTER PASSWORD to change password for the CDF database");
		} else {
			if (this.sqlDatabase.isHasUncommitted()) {
				console.error("Uncommitted transactions pending. Commit first before updating password");
			} else {
				console.warn("This will change the connection password in the target database");
				console.println("Do you want to continue [y/n]?");
				String confirm = console.readLine();
				if (StringUtils.isNotBlank(confirm) && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
					console.println("New password for connection '" + this.getPlatform() + "'");
					console.println("Authenticate with master password:");
					String masterPassword = console.readPassword();
					getDatabaseConnectionsVault().setPassword(masterPassword);
					if (getDatabaseConnectionsVault().isValidPassword()) {
						console.println("Enter NEW password for connection '" + this.getPlatform() + "':");
						String newConnPwd = console.readPassword();
						if (StringUtils.isNotBlank(newConnPwd)) {
							console.println("Retype NEW password for verification purpose:");
							String newConnPwdVerif = console.readPassword();
							if (newConnPwd.equals(newConnPwdVerif)) {
								// Proceed with password change
								// 1. Collect data
								DatabaseDefinition pl = getDatabaseConnectionsVault().getDatabaseConnection(this.platform);
								String userName = pl.getUserName();
								String platformsFileName = consoleSettings.getProtectedPlatformsFileName();
								String oldPassword = pl.getUserPassword();
								String newPassword = newConnPwd;
								try {
									// Change password in $CLI
									getDatabaseConnectionsVault().updateDatabaseDefinitionPassword(platformsFileName, platform, newPassword);
									// Change password in the remote system
									this.sqlDatabase.changePassword(userName, oldPassword, newPassword);
									console.println("Password updated for connection '" + this.getPlatform() + "'", ShellConsole.MSG_INFO);
									this.sqlDatabase.commit();
								} catch (Exception e) {
									//e.printStackTrace();
									console.error(e.getLocalizedMessage());
									console.println("Command aborted. Reverting back to initial password");
									getDatabaseConnectionsVault().updateDatabaseDefinitionPassword(platformsFileName, platform, oldPassword);
								}
							} else {
								console.error("New password and verification are different, command aborted");
							}
						} else {
							console.error("New password cannot be null or space, command aborted");
						}
					} else {
						console.error("Invalid master password, command aborted");
					}
				} else {
					console.println("Command aborted");
				}
			}
		}
	}

	@Override
	public String getDescription() {
		return ("change password of the current connection");
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "none. Command will prompt for old and new password";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "SET PASSWORD";
	}
}
