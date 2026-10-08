package com.upandcoding.broadsql.controller.shell.output;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;

public class ConsoleUtils {
	
	private static final Logger log = LoggerFactory.getLogger(ConsoleUtils.class);

	private static final String hrChar = "=";
	private static final String limitChar = "|";

	public static String getShortenedPaddedText(String text, String paddingText, int size) {
		if (StringUtils.isBlank(paddingText)) {
			paddingText = " ";
		}
		if (StringUtils.isNotBlank(text)) {
			String result = getPaddedTextWithHalfBorders(StringUtils.abbreviate(text, size - 1), paddingText, size);
			return result;
		} else {
			return getPaddedTextWithHalfBorders(paddingText, paddingText, size);
		}
	}

	public static String getPaddedTextWithHalfBorders(String text, String paddingText, int size) {
		String result = StringUtils.rightPad(text, size, paddingText);
		result = limitChar + " " + result;
		return result;
	}

	public static String getPaddedTextWithBorders(String text, int size) {
		String result = StringUtils.rightPad(text, size);
		result = limitChar + " " + result + " " + limitChar;
		return result;
	}

	private static String getFinalBlock(List<String> texts) {
		StringBuffer disp = new StringBuffer();
		int length = 0;
		// Get max length
		for (String text : texts) {
			if (text.length() > length) {
				length = text.length();
			}
		}
		// Border
		String hr = StringUtils.repeat(hrChar, length);
		hr = getPaddedTextWithBorders(hr, length);

		disp.append(hr);
		disp.append("\n");

		// Format and add to buffer
		for (String text : texts) {
			String fmt = getPaddedTextWithBorders(text, length);
			disp.append(fmt);
			disp.append("\n");
		}

		disp.append(hr);
		disp.append("\n");

		return disp.toString();
	}

	public static String getTitleAndVersion() {

		String title = SpringPropertiesConfig.APP_TITLE;
		String version = SpringPropertiesConfig.APP_VERSION;
		String site = SpringPropertiesConfig.APP_SITE;

		String app = title + " " + version;

		List<String> list = new LinkedList<>();
		list.add(app);
		list.add("");
		list.add("Documentation and latest release available on:");
		list.add(site);

		return getFinalBlock(list);
	}

	public static String getFormattedElapsedTime(Instant start, Instant end) {
		String elapsedTime = "";
		String unit = "s";
		if (start!=null && end!=null) {
			double duration = Double.valueOf((Duration.between(start, end).toMillis())) / 1000;
			DecimalFormat fmt = new DecimalFormat("0.00");
			if (duration<1) {
				fmt = new DecimalFormat("0");
				duration = (Duration.between(start, end).getNano()) / 1000000;
				unit = "ms";					
			}
			elapsedTime = "rows fetched in " + fmt.format(duration) + " " + unit;	
		}
		return elapsedTime;
	}

}
