package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Small display-formatting helpers shared by every {@code LIB}/{@code SCRIPT} {@code LIST}/{@code FIND}
 * command, so the grid looks identical regardless of which command built it.
 */
public final class CatalogFormat {

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

	private CatalogFormat() {
	}

	public static synchronized String formatModified(long millis) {
		return millis > 0 ? DATE_FORMAT.format(new Date(millis)) : "";
	}

	/** {@code timestamp} is stored as {@code yyyyMMdd-HHmmss} (see {@link FileCatalog}'s archive naming). */
	public static String formatArchiveTimestamp(String timestamp) {
		if (timestamp == null || timestamp.length() != 15) {
			return timestamp;
		}
		return timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-" + timestamp.substring(6, 8)
				+ " " + timestamp.substring(9, 11) + ":" + timestamp.substring(11, 13);
	}
}
