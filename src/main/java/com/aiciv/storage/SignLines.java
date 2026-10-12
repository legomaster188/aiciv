package com.aiciv.storage;

import java.util.ArrayList;
import java.util.List;

/** Fits text onto a sign: at most 4 lines of 15 characters, 60 in all, word-wrapped. Pure. */
public final class SignLines {
    public static final int LINES = 4, LINE_LENGTH = 15, MAX_LENGTH = LINES * LINE_LENGTH;

    private SignLines() {}

    public static List<String> wrap(String text) {
        String s = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).stripTrailing();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            while (word.length() > LINE_LENGTH) { // longer than a line by itself: split it
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, LINE_LENGTH));
                word = word.substring(LINE_LENGTH);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > LINE_LENGTH) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.size() > LINES ? new ArrayList<>(lines.subList(0, LINES)) : lines;
    }
}
