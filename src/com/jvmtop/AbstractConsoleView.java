package com.jvmtop;

import java.util.Collections;
import java.util.Comparator;
import java.util.Formatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public abstract class AbstractConsoleView implements ConsoleView {
	public long toMB(long bytes) {
		return (bytes / 1024L / 1024L);
	}

	public String toHHMM(long millis) {
		StringBuilder sb = new StringBuilder();
		Formatter formatter = new Formatter(sb);
		formatter.format(
				"%2d:%2dm",
				new Object[] { Long.valueOf(millis / 1000L / 3600L),
						Long.valueOf(millis / 1000L / 60L % 60L) });
		return sb.toString();
	}

	public String rightStr(String str, int length) {
		return str.substring(Math.max(0, str.length() - length));
	}

	public String leftStr(String str, int length) {
		return str.substring(0, Math.min(str.length(), length));
	}

	public String join(List<String> list, String delim) {
		StringBuilder sb = new StringBuilder();

		String loopDelim = "";

		for (String s : list) {
			sb.append(loopDelim);
			sb.append(s);

			loopDelim = delim;
		}

		return sb.toString();
	}

	public Map sortByValue(Map map, boolean reverse) {
		List list = new LinkedList(map.entrySet());
		Collections.sort(list, new Comparator() {
			public int compare(Object o1, Object o2) {
				return ((Comparable) ((Map.Entry) o1).getValue())
						.compareTo(((Map.Entry) o2).getValue());
			}
		});
		if (reverse) {
			Collections.reverse(list);
		}

		Map result = new LinkedHashMap();
		for (Iterator it = list.iterator(); it.hasNext();) {
			Map.Entry entry = (Map.Entry) it.next();
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}
}