package com.upandcoding.broadsql.controller;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StartupCommandLineParser {

	private final static Logger log = LoggerFactory.getLogger(StartupCommandLineParser.class);

	String[] args;
	// Parameters actually entered by users
	private final HashMap<String, String> parameters = new HashMap<>();
	// Parameters authorized: if blank all are authorized
	private final HashMap<String, String> authorizedParameters = new HashMap<>();

	public StartupCommandLineParser() {
	}

	public StartupCommandLineParser(String[] a) {
		this.args = a;
	}

	/*
	Displays help for this program: how to use the command line options
	 */
	public void printUsage() {
		System.out.println("Usage: java BroadSQL <options>");
		if (authorizedParameters != null && !authorizedParameters.isEmpty()) {
			System.out.println("where possible options include:");
			Set<String> keys = authorizedParameters.keySet();
			for (String key : keys) {
				System.out.println("  -" + key + "=" + authorizedParameters.get(key));
			}
		}
		System.out.println("");
		System.exit(0);
	}

	public void addAuthorizedParameter(String name, String descr) {
		if (name != null && !name.trim().equals("")) {
			if (descr == null) {
				descr = "No description available for this option";
			}
			this.authorizedParameters.put(name.trim(), descr.trim());
		}
	}

	/*
	Parses the command line entered by the user
	 */
	public boolean parse() {
		boolean resume = true;
		if (args != null && args.length > 0) {
			try {
				//Retrieve list of arguments
				for (int i = 0; i < args.length; i++) {
					String param = args[i];
					String[] elems = null;
					String value = null;
					String name = null;
					if (param != null && param.indexOf("=") > 0) {
						elems = param.split("=");
						if (elems.length > 1) {
							name = elems[0];
							if (elems.length == 2) {
								value = elems[1];
							} else {
								int p = param.indexOf("=");
								String tmp = param.substring(p + 1);
								int n = tmp.indexOf(" -");
								if (n > 0) {
									value = tmp.substring(0, n);
								} else {
									value = tmp;
								}
							}
						}
					} else if (param != null) {
						name = param;
						value = null;
					}
					if (StringUtils.isNotBlank(name) && name.startsWith("-")) {
						String paramName = name.substring(1, name.length());
						boolean shouldAdd = true;
						if (this.authorizedParameters != null && !this.authorizedParameters.isEmpty()) {
							Set<String> pNames = this.authorizedParameters.keySet();
							if (pNames != null && !pNames.isEmpty() && !pNames.contains(paramName)) {
								shouldAdd = false;
							}
						}
						if (shouldAdd) {
							this.parameters.put(paramName, value);
						}
						if (param.toLowerCase().contains("-help")) {
							printUsage();
							resume = false;
						}
					}
				}//for
			} catch (ArrayIndexOutOfBoundsException aie) {
				System.out.println("");
				System.out.println("Error: invalid parameters");
				System.out.println("Type -help for details about the parameters");
				resume = false;
			}
		} else {
			// No command line arguments
			System.out.println("");
			System.out.println("Error: Missing parameters (" + args.length + ")");
			System.out.println("Type -help for details about the parameters");
			resume = false;
		}
		return resume;
	}

	public String getParameterValue(String name) {
		String result = null;
		if (this.parameters.containsKey(name)) {
			result = this.parameters.get(name);
		}
		return (result);
	}

	public void printParameters() {
		if (this.parameters != null && !this.parameters.isEmpty()) {
			Set<String> keys = this.parameters.keySet();
			Iterator<String> it = keys.iterator();
			while (it.hasNext()) {
				String name = it.next();
				String value = this.parameters.get(name);
				System.out.println(name + ": " + value);
			}
		}
	}

	public String[] getArgs() {
		return args;
	}

	public void setArgs(String[] args) {
		this.args = args;
	}

}