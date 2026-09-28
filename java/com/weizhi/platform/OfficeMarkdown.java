package com.weizhi.platform;

import java.util.ArrayList;
import java.util.List;

/** Minimal Markdown subsets for docx / pptx MVP (headings, bullets, slide breaks). */
final class OfficeMarkdown {
    static final class Block {
        enum Kind {
            HEADING, PARAGRAPH, BULLET
        }

        final Kind kind;
        final int level;
        final String text;

        Block(Kind kind, int level, String text) {
            this.kind = kind;
            this.level = level;
            this.text = text;
        }
    }

    static final class Slide {
        final String title;
        final List<String> bullets;

        Slide(String title, List<String> bullets) {
            this.title = title == null ? "" : title;
            this.bullets = bullets;
        }
    }

    private OfficeMarkdown() {
    }

    static List<Block> parseDocument(String md) {
        List<Block> blocks = new ArrayList<>();
        if (md == null) {
            return blocks;
        }
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                if (level < line.length() && line.charAt(level) == ' ') {
                    blocks.add(new Block(Block.Kind.HEADING, level, line.substring(level + 1).strip()));
                    continue;
                }
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                blocks.add(new Block(Block.Kind.BULLET, 0, line.substring(2).strip()));
                continue;
            }
            blocks.add(new Block(Block.Kind.PARAGRAPH, 0, line.strip()));
        }
        return blocks;
    }

    static List<Slide> parseSlides(String md) {
        List<Slide> slides = new ArrayList<>();
        if (md == null || md.isEmpty()) {
            slides.add(new Slide("Slide", List.of()));
            return slides;
        }
        String normalized = md.replace("\r\n", "\n").replace('\r', '\n');
        String[] chunks = normalized.split("\n---\n");
        for (String chunk : chunks) {
            String trimmed = chunk.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String title = "Slide";
            List<String> bullets = new ArrayList<>();
            for (String raw : trimmed.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("#")) {
                    title = line.replaceFirst("^#+\\s*", "").strip();
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    bullets.add(line.substring(2).strip());
                } else {
                    bullets.add(line);
                }
            }
            slides.add(new Slide(title, bullets));
        }
        if (slides.isEmpty()) {
            slides.add(new Slide("Slide", List.of()));
        }
        return slides;
    }
}
