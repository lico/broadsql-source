package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Queries from an external text file
 * queries are delimited by semicolons
 * ignores blocks of comments in the file (either starting with -- or surrounded by / * * /
 */
public class ScriptCommandsList extends ArrayList<ScriptCommand> {

	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory.getLogger(ScriptCommandsList.class);

	String fileName;

	public ScriptCommandsList(String fName) {
		this.fileName = fName;
	}

	public void load() throws IOException {
		FileReader fr = new FileReader(this.fileName);
		BufferedReader br = new BufferedReader(fr);
		String line = "";
		StringBuilder bs = new StringBuilder();
		boolean skipLines = false;
		while ((line = br.readLine()) != null) {
			if (!line.startsWith("--")) {
				if (line.indexOf("/*") >= 0) {
					// The line contains an open comment tag
					if (line.indexOf("*/") >= 0) {
						// The comment starts and ends on the same line
						line = StringUtils.substringBefore(line, "/*") + StringUtils.substringAfterLast(line, "*/");
						bs.append(line);
						bs.append(" ");
						skipLines = false;
					} else {
						// The comment starts on the line but does not stop on the line
						line = StringUtils.substringBefore(line, "/*");
						bs.append(line);
						bs.append(" ");
						skipLines = true;
					}
				} else if (line.indexOf("*/") >= 0) {
					// The line contains a closing comment tag but NO opening tag
					line = StringUtils.substringAfterLast(line, "*/");
					bs.append(line);
					bs.append(" ");
					skipLines = false;
				} else {
					// The line contains neither an open nor a closing tag
					if (!skipLines) {
						bs.append(line);
						bs.append(" ");
					}
				}
			} else {
				// It's a comment, we ignore the line
			}
		} 

		String allInput = bs.toString();
		String[] allInputTab = allInput.split(";");
		ArrayList<String> queries = new ArrayList<String>(Arrays.asList(allInputTab));
		if (queries != null && !queries.isEmpty()) {
			for (String q : queries) {
				ScriptCommand query = new ScriptCommand(q);
				this.add(query);
			} // for
		}
		// log.debug("Query="+bs.toString());
		fr.close();
	}

}
