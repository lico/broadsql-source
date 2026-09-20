package com.upandcoding.broadsql.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.upandcoding.broadsql.controller.config.SpringMainConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.Session;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;

public class BroadSQL {

	private final static Logger log = LoggerFactory.getLogger(BroadSQL.class.getName());

	public static void main(String[] args) {

		ApplicationContext context = new AnnotationConfigApplicationContext(SpringMainConfig.class);

		StartupCommandLineParser cmdLine = (StartupCommandLineParser) context.getBean("cmdLineParser");
		cmdLine.setArgs(args);
		
		if (cmdLine.parse()) {

			Session session = (Session) context.getBean("session");
			
			CommandInterpreter commandInterpreter = (CommandInterpreter) context.getBean("consoleCommandInterpreter");
			commandInterpreter.setPlatform(cmdLine.getParameterValue("to"));
			ShellConsole console = (ShellConsole) context.getBean("shellConsole");

			try {
				
				// Display program name and version
				console.writeln("");
				console.writeln(ConsoleUtils.getTitleAndVersion());

				// DefaultFileFormat (BroadSQL.ini): report once at startup if missing/invalid,
				// see docs/TECHNICAL_CHANGE.md ("Export ODS")
				ConsoleSettings consoleSettings = (ConsoleSettings) context.getBean("consoleSettings");
				String defaultFileFormatNotice = consoleSettings.getDefaultFileFormatStartupNotice();
				if (defaultFileFormatNotice != null) {
					console.info(defaultFileFormatNotice);
				}

				// DefaultEnvironment (BroadSQL.ini): report once at startup if missing, see docs/TODO.md, item 5
				String defaultEnvironmentNotice = consoleSettings.getDefaultEnvironmentStartupNotice();
				if (defaultEnvironmentNotice != null) {
					console.info(defaultEnvironmentNotice);
				}

				// Scripts (BroadSQL.ini): report once at startup if missing, see docs/TECHNICAL_CHANGE.md
				String scriptsPathNotice = consoleSettings.getScriptsPathStartupNotice();
				if (scriptsPathNotice != null) {
					console.info(scriptsPathNotice);
				}

				// JsScripts (BroadSQL.ini): report once at startup if missing, see docs/LIGHT_SCRIPTING.md
				String jsScriptsPathNotice = consoleSettings.getJsScriptsPathStartupNotice();
				if (jsScriptsPathNotice != null) {
					console.info(jsScriptsPathNotice);
				}

				// Authenticate
				session.authenticate(console);

				// Execute program until end
				commandInterpreter.run();

				// Close the session
				session.close();
				
				console.writeln("");

			} catch (BroadSQLException ex) {
				console.error(ex);
				log.warn(ex.getLocalizedMessage());
			} catch (Exception e) {
				console.error(e);
				log.warn(e.getLocalizedMessage());
			}
		}
		
		// Close context if necessary
		if (context!=null) {
			((AnnotationConfigApplicationContext)context).close();
		}
	}
}
