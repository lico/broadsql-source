package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Changes the master password protecting the CDF (Connections Definition File) itself:
 * {@code SET MASTER PASSWORD}, no arguments - runs as an interactive prompt.
 *
 * <p>Asks for the current master password to re-authenticate, then for the new password twice for
 * verification. On success, the current connection is closed, the CDF is re-encrypted with the new
 * master password, and the session is asked to log in again with it - this affects every connection
 * defined in the CDF, not just the one currently open.
 *
 * <p>Aborts without making any change if authentication fails, the new password is blank, or the two
 * entries for the new password don't match.
 */
public class CommandSetMasterPassword extends Command {
	
	private static final Logger log = LoggerFactory.getLogger(CommandSetMasterPassword.class);

    static final HashMap<String, Character> names = new HashMap<String, Character>();

    public CommandSetMasterPassword() {
        super("SET MASTER PASSWORD", "SET MAPA");
    }

    @Override
    public void execute(String query) throws BroadSQLException {
    	
    	boolean success = false;
    	
    	//shellConsole.println("New master password for Connections Definition File");
		console.println("Please login:");
        String masterPassword = console.readPassword();
        getDatabaseConnectionsVault().setPassword(masterPassword);
        if (getDatabaseConnectionsVault().isValidPassword()) {
            console.println("Enter NEW master password:");
            String newMasterPwd = console.readPassword();
            if (newMasterPwd != null && !newMasterPwd.trim().equals("")) {
                console.println("Re-type NEW master password for verification purpose:");
                String newMasterPwdVerif = console.readPassword();
                if (newMasterPwd.equals(newMasterPwdVerif)) {
                	this.sqlDatabase.close(false);
                	getDatabaseConnectionsVault().changeMasterPassword(platformsFileName, masterPassword, newMasterPwd);
                    console.println("");
                    console.info("Master password successfully updated.");
                    this.sqlDatabase.getPlatform().setUserPassword(newMasterPwdVerif + " " + newMasterPwdVerif);
                    
                    success = true;
                } else {
                    console.error("New password and verification are different, command aborted");
                }
            } else {
                console.error("New password cannot be null or space, command aborted");
            }
        } else {
            console.error("Invalid password, command aborted");
        }
        
        if (success) {
        	// This code is outside the block because in case of login error this will fail correctly
            console.println("Please login.");
            this.getSession().authenticate(console);
        }
    }

    @Override
    public String getDescription() {
        return ("Change master password for Connections Definition File");
    }

	@Override
    public String getArguments() {
	    return "none. Command will prompt for old and new password";
    }

	@Override
    public String getExamples() {
	    return "SET MASTER PASSWORD;";
    }
}
