package com.weizhi.agent.tool.util;

public final class GlobToRegex {

    private GlobToRegex() {
    }

    public static String convert(String glob) {
        if (glob == null || glob.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                            sb.append("(?:.*/)?");
                            i += 3;
                        } else {
                            sb.append(".*");
                            i += 2;
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                    break;
                case '?':
                    sb.append('.');
                    i++;
                    break;
                case '{':
                    int end = glob.indexOf('}', i);
                    if (end < 0) {
                        sb.append("\\{");
                        i++;
                        break;
                    }
                    String[] opts = glob.substring(i + 1, end).split(",");
                    sb.append("(");
                    for (int j = 0; j < opts.length; j++) {
                        if (j > 0) {
                            sb.append("|");
                        }
                        sb.append(opts[j]);
                    }
                    sb.append(")");
                    i = end + 1;
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '\\':
                    sb.append("\\").append(c);
                    i++;
                    break;
                default:
                    sb.append(c);
                    i++;
            }
        }
        return sb.toString();
    }
}
