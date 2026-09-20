package com.upandcoding.broadsql.controller.shell.output;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.dao.DatabaseConnection;

public class ConsoleLogger {

	private static final Logger appLogger = LoggerFactory.getLogger(ConsoleLogger.class);

	@Autowired
	public ConsoleSettings consoleSettings;

	@Autowired
	public DatabaseConnection database;
	
	@Autowired
	public ShellConsole console;

	private static final String defaultExtension = ".log";
	private static final String defaultFileName = "LOGFILE";

	private String filePath = "";
	private long maxNrOfLines = 2000000; // Max number of lines in a single log file
	private long nrOfLines = 0; // Nr of lines in current log file
	private boolean logDisplayDateAndTime = true;
	private final String dateFormat = "yyyy.MM.dd HH:mm:ss";

	public ConsoleLogger() {
	}

	public boolean isLogDisplayDateAndTime() {
		return logDisplayDateAndTime;
	}

	public void setLogDisplayDateAndTime(boolean logDisplayDateAndTime) {
		this.logDisplayDateAndTime = logDisplayDateAndTime;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public long getLines() {
		return nrOfLines;
	}

	public void setLines(long lines) {
		this.nrOfLines = lines;
	}

	public long getMaxLines() {
		return maxNrOfLines;
	}

	public void setMaxLines(long nrLines) {
		maxNrOfLines = nrLines;
	}

	public String getDateFormat() {
		return dateFormat;
	}

	/**
	 * This method generates a file name with the current date. It uses a String
	 * for the prefix of the file name and adds the date. For example, if the
	 * root is "mylog" then the file name will be : mylog-YYYYMMDD.txt, where
	 * YYYYMMDD is the file's creation date.
	 *
	 * @param root (String) is the prefix of the file name
	 * @param extension(String) the file extension, eg txt
	 * @return (String) a file name with the file's creation date
	 */
	public String createDatedFileName(String root, String extension) {
		if (root == null) {
			root = defaultFileName;
		}
		Date now = new Date();
		return (root + "_" + DateFormatUtils.format(now, "yyyyMMdd") + extension);
	}

	/**
	 * This method generates a file name with the current date. It uses a String
	 * for the prefix of the file name and adds the date. For example, if the
	 * root is "mylog" then the file name will be : mylog-YYYYMMDD.txt, where
	 * YYYYMMDD is the file's creation date.
	 *
	 * @param root (String) is the prefix of the file name
	 * @return (String) a file name with the file's creation date
	 */
	public String createDatedFileName(String root) {
		return (createDatedFileName(root, defaultExtension));
	}

	/**
	 * This method generates a file name with the current date. The resulting
	 * file name will look like : log-YYYYMMDD.txt, where YYYYMMDD is the file's
	 * creation date.
	 *
	 * @return (String) a file name with the file's creation date
	 */
	public String createDatedFileName() {
		return (createDatedFileName(null));
	}

	private void checkSize() {
		if (nrOfLines < maxNrOfLines) {
			nrOfLines++;
		} else {
			File logFile = new File(filePath);
			if (logFile.exists()) {
				String root = StringUtils.substringBefore(logFile.getName(), "-") + "_";
				String newName = createDatedFileName(root);
				filePath = StringUtils.substringBeforeLast(logFile.getPath(), SpringPropertiesConfig.getFileSep()) + SpringPropertiesConfig.getFileSep() + newName;
				nrOfLines = 0;
			}
		}
	}

	/**
	 * Gets the message prefixed by the date
	 *
	 * @input msg String representing a message
	 * @return String msg prefixed by the current date and time
	 */
	public String getDatedMessage(String msg) {
		Date now = new Date();
		return (DateFormatUtils.format(now, dateFormat) + " " + msg);
	}

	public void write(String message) throws IOException {
		String fileName = consoleSettings.getLogFileName();
		if (StringUtils.isNotBlank(database.getPlatform().getId())) {
			fileName = consoleSettings.getLogFileName() + "_" + database.getPlatform().getId();
		}
		setFilePath(createDatedFileName(consoleSettings.getLogFolderName() + fileName, ".log"));
		setLogDisplayDateAndTime(logDisplayDateAndTime);
		print(message);
	}

	public void writeln(String message) throws IOException {
		write(message + "\n");
	}

	/**
	 * Writes a piece of string in the log file
	 * @throws IOException 
	 */
	public void print(String text) throws IOException {
		if (logDisplayDateAndTime) {
			text = getDatedMessage(text);
		}

		BufferedWriter output = null;
		try {
			output = new BufferedWriter(new FileWriter(filePath, true));
			output.write(text);
			checkSize();
		} catch (IOException e) {
			console.error(e);
		} finally {
			if (output != null) {
				try {
					output.close();
				} catch (IOException ex) {
					throw ex;
				}
			}
		}
	}

	/**
	 * Writes a piece of string in the log file AND goes to the next line
	 */
	public void println(String text) throws IOException {
		print(text + "\n");
	}

}
