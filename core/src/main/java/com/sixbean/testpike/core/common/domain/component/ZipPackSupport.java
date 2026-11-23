package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Common zip package read/write component (streaming dual gates for zip bombs and package-building logic reused at least twice across SKILL/Tools domains).
 * <p>Read is guarded unzip (same criteria): {@link ZipInputStream} counts while reading.
 * Throws immediately when limits are exceeded—the entry count {@value #ZIP_MAX_ENTRIES} and uncompressed total bytes {@value #ZIP_MAX_UNCOMPRESSED}.
 * (50MB) dual gates reject writing over-limit content to disk or retaining it in memory; entries are returned in original zip order (LinkedHashMap).
 * The SKILL 'first .md entry' semantics depend on order.
 * <p>Write builds a zip (shared by market download packages and test fixtures, UTF-8 entry names) with single-entry and multi-entry overloads.
 * <p>Error text is unified across domains (plain language plus suspected bomb hint), and scattered rewriting is forbidden to prevent drift between domains.
 *
 */
public final class ZipPackSupport {

 /** Zip entry count limit. */
    public static final int ZIP_MAX_ENTRIES = 1000;
 /** Zip uncompressed total byte limit is 50MB. */
    public static final long ZIP_MAX_UNCOMPRESSED = 50L * 1024 * 1024;
    /** Single-entry read buffer chunk (for streaming counting; one-shot readAllBytes is forbidden to prevent OOM). */
    private static final int CHUNK = 64 * 1024;

    private ZipPackSupport() {
    }

    /**
     * Safe unzip with dual bomb gates (returns a mapping from entry names to content bytes in original zip order; over-limit returns a plain-language 400 and forbids disk persistence).
     */
    public static Map<String, byte[]> readEntries(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            long total = 0;
            int count = 0;
            ZipEntry entry;
            byte[] buf = new byte[CHUNK];
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > ZIP_MAX_ENTRIES) {
                    throw new ServiceException("zip 包条目数超过上限 " + ZIP_MAX_ENTRIES
                            + "（疑似 zip 炸弹，已拒绝解压）", BizErrorCode.VALIDATION_ERROR);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > ZIP_MAX_UNCOMPRESSED) {
                        throw new ServiceException("zip 包解压后总大小超过上限 50MB"
                                + "（疑似 zip 炸弹，已拒绝解压落盘）", BizErrorCode.VALIDATION_ERROR);
                    }
                    bos.write(buf, 0, n);
                }
                entries.putIfAbsent(entry.getName(), bos.toByteArray());
                zis.closeEntry();
            }
            return entries;
        } catch (ZipException e) {
            throw new ServiceException("不是有效的 zip 文件（文件损坏或扩展名与实际格式不符）",
                    BizErrorCode.VALIDATION_ERROR);
        } catch (IOException e) {
            throw new ServiceException("zip 读取失败：" + e.getMessage(), BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Builds a single-entry zip (shared by market download and test fixtures; UTF-8 entry names). */
    public static byte[] buildZip(String entryName, String text) {
        return buildZip(Map.of(entryName, text));
    }

    /** Builds a multi-entry zip (entries are written in the iteration order of the supplied Map; UTF-8 entry names). */
    public static byte[] buildZip(Map<String, String> nameToText) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> e : nameToText.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
