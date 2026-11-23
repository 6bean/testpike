package com.sixbean.testpike.core.document.domain.component;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Component
public class MarkdownChunkSplitter {

 
    static final int CHUNK_CHAR_BUDGET = 480;
    
    private static final int CHAPTER_MAX_LEN = 200;

    
    public List<ChunkPiece> split(String text) {
        List<ChunkPiece> pieces = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return pieces;
        }
        String[] headings = new String[7];
        StringBuilder pending = new StringBuilder();
        String pendingChapter = null;
        int currentLevel = 0;

        for (String block : text.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n{2,}|(?<!\n)\n(?!\n)")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int level = headingLevel(trimmed);
            if (level > 0) {
                pendingChapter = flush(pieces, pendingChapter, pending);
                String title = trimmed.substring(level).strip();
                headings[level] = title;
                for (int deeper = level + 1; deeper <= 6; deeper++) {
                    headings[deeper] = null;
                }
                currentLevel = level;
                continue;
            }
            String chapter = chapterOf(headings);
            if (pending.length() > 0 && (!Objects.equals(chapter, pendingChapter)
                    || pending.length() + trimmed.length() > CHUNK_CHAR_BUDGET)) {
                pendingChapter = flush(pieces, pendingChapter, pending);
            }
            if (pendingChapter == null) {
                pendingChapter = chapter;
            }
            if (trimmed.length() > CHUNK_CHAR_BUDGET) {
                pendingChapter = flush(pieces, pendingChapter, pending);
                pendingChapter = chapter;
                appendResplit(pieces, chapter, trimmed);
                continue;
            }
            if (pending.length() > 0) {
                pending.append("\n");
            }
            pending.append(trimmed);
        }
        flush(pieces, pendingChapter, pending);
        return pieces;
    }

    
    private static int headingLevel(String block) {
        if (block.startsWith("```")) {
            return -1;
        }
        for (int level = 1; level <= 6; level++) {
            String prefix = "#".repeat(level);
            if (block.startsWith(prefix) && block.length() > level
                    && block.charAt(level) == ' ') {
                if (level == 6 && block.length() > 7 && block.charAt(7) == '#') {
                    return -1;
                }
                return level;
            }
        }
        return -1;
    }

    private static String chapterOf(String[] headings) {
        StringBuilder sb = new StringBuilder();
        for (int level = 1; level <= 6; level++) {
            if (headings[level] == null) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(headings[level]);
        }
        String chapter = sb.toString();
        if (chapter.length() > CHAPTER_MAX_LEN) {
            chapter = chapter.substring(0, CHAPTER_MAX_LEN);
        }
        return chapter.isEmpty() ? null : chapter;
    }

    
    private static void appendResplit(List<ChunkPiece> pieces, String chapter, String paragraph) {
        for (int from = 0; from < paragraph.length(); from += CHUNK_CHAR_BUDGET) {
            String slice = paragraph.substring(from,
                    Math.min(paragraph.length(), from + CHUNK_CHAR_BUDGET));
            if (!slice.isBlank()) {
                pieces.add(new ChunkPiece(chapter, slice));
            }
        }
    }

    
    private static String flush(List<ChunkPiece> pieces, String chapter, StringBuilder pending) {
        if (pending.length() > 0) {
            String content = pending.toString().strip();
            if (!content.isEmpty()) {
                pieces.add(new ChunkPiece(chapter, content));
            }
            pending.setLength(0);
        }
        return null;
    }
}
