package com.upandcoding.broadsql.dao.load;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Reads a {@code LOAD} source file using real RFC 4180 CSV parsing (Apache Commons CSV, already a
 * project dependency - see {@code CsvColumnListSource}) - quoted values, embedded separators/quotes
 * and multi-line values are all handled correctly, unlike the legacy {@code directLoad}/
 * {@code directLoadByBatch}'s plain {@code line.split(";")}, which cannot represent a value
 * containing the separator or a newline at all.
 *
 * <p>The header row is mandatory and its column order is preserved (this becomes the generated
 * {@code INSERT} column order). A header appearing more than once is rejected outright rather than
 * silently resolved to "the first one" - reordering headers must never silently change which values
 * land in which column, so an ambiguous file is refused instead of guessed at. A leading byte-order
 * mark on the first header (common for a file produced by Excel) is stripped before matching.
 */
public final class LoadSourceReader {

	private final List<String> headers;
	private final List<CSVRecord> records;

	private LoadSourceReader(List<String> headers, List<CSVRecord> records) {
		this.headers = headers;
		this.records = records;
	}

	public static LoadSourceReader read(String filePath, char separator) throws BroadSQLException {
		File file = new File(filePath);
		if (!file.isFile()) {
			throw new BroadSQLException("Source file '" + filePath + "' not found.");
		}

		CSVFormat format = CSVFormat.DEFAULT.builder()
				.setDelimiter(separator)
				.setHeader()
				.setSkipHeaderRecord(true)
				.setIgnoreSurroundingSpaces(true)
				.setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
				.build();

		try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
				CSVParser parser = format.parse(reader)) {

			List<String> headerList = new ArrayList<>();
			for (String header : parser.getHeaderNames()) {
				headerList.add(stripLeadingBom(header));
			}
			if (headerList.isEmpty()) {
				throw new BroadSQLException("Source file '" + filePath + "' has no header row.");
			}

			List<CSVRecord> recordList = new ArrayList<>();
			for (CSVRecord record : parser) {
				recordList.add(record);
			}
			return new LoadSourceReader(headerList, recordList);

		} catch (IOException e) {
			throw new BroadSQLException("Error reading source file '" + filePath + "': " + e.getLocalizedMessage());
		} catch (IllegalArgumentException iae) {
			throw new BroadSQLException("Source file '" + filePath + "' could not be parsed - it likely contains a duplicate "
					+ "header column: " + iae.getLocalizedMessage());
		}
	}

	public List<String> getHeaders() {
		return headers;
	}

	public List<CSVRecord> getRecords() {
		return records;
	}

	private static String stripLeadingBom(String s) {
		return (s != null && !s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
	}
}
