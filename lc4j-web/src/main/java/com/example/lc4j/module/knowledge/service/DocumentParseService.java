package com.example.lc4j.module.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析服务（对齐 java-ai-native DocumentParseService）
 *
 * 支持 PDF / DOCX / TXT / MD 四种格式；
 * 切片策略：按段落聚合 + 动态 chunk size（大文件大块省 API，小文件小块提精度）+ 10% 重叠
 */
@Slf4j
@Service
public class DocumentParseService {

    /**
     * 根据文件大小动态计算 chunk size（字符数），平衡语义完整性与 API 调用量。
     *
     * 原则：
     *   - 大文件用更大 chunk → 减少块数 → 降低 embedding API 调用总量
     *   - 小文件用较小 chunk → 提高检索精度
     *   - 火山 API 限流 QPS≈5，需控制总块数在 100-200 以内
     */
    private static int calcChunkSize(long fileSize) {
        // 估算文本量：PDF/DOCX 纯文本约占文件大小的 5-15%，取 10%
        long estimatedTextBytes = Math.max(fileSize / 10, 1);

        if (estimatedTextBytes <= 5_000)       return 800;   // 极小文档（<50KB）
        if (estimatedTextBytes <= 20_000)      return 1024;  // 小文档（50KB-200KB）
        if (estimatedTextBytes <= 50_000)      return 1536;  // 中等（200KB-500KB）
        if (estimatedTextBytes <= 100_000)     return 2048;  // 较大（500KB-1MB）
        if (estimatedTextBytes <= 500_000)     return 3072;  // 大文档（1MB-5MB）
        if (estimatedTextBytes <= 1_000_000)   return 4096;  // 超大（5MB-10MB）
        return 4096;  // 巨型文档（>10MB），最大 chunk
    }

    private static int calcOverlap(int chunkSize) {
        // overlap 为 chunk 的 10%（至少 50 字符）
        return Math.max(chunkSize / 10, 50);
    }

    public String extractText(Path filePath) throws IOException {
        String ext = getFileExtension(filePath.toString()).toLowerCase();

        return switch (ext) {
            case "pdf" -> extractFromPdf(Files.newInputStream(filePath));
            case "docx" -> extractFromDocx(Files.newInputStream(filePath));
            case "txt", "md" -> extractFromPlainText(Files.newInputStream(filePath));
            default -> throw new IllegalArgumentException("Unsupported file type: " + ext);
        };
    }

    public List<String> chunkText(String text, long fileSize) {
        int chunkSize = calcChunkSize(fileSize);
        int overlap = calcOverlap(chunkSize);
        log.info("[chunk] textLength={}, fileSize={}, chunkSize={}, overlap={}, estimatedChunks={}",
                text.length(), fileSize, chunkSize, overlap, text.length() / (chunkSize - overlap));
        return chunkText(text, chunkSize, overlap);
    }

    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (currentChunk.length() + trimmed.length() > chunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                String overlapStr = currentChunk.length() > overlap
                        ? currentChunk.substring(currentChunk.length() - overlap)
                        : currentChunk.toString();
                currentChunk = new StringBuilder(overlapStr);
            }

            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmed);
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks.stream()
                .flatMap(c -> splitIfTooLong(c, chunkSize, overlap).stream())
                .toList();
    }

    private List<String> splitIfTooLong(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text.length() <= chunkSize) {
            result.add(text);
            return result;
        }
        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            int end = Math.min(i + chunkSize, text.length());
            result.add(text.substring(i, end));
            if (end == text.length()) break;
        }
        return result;
    }

    private String extractFromPdf(InputStream is) throws IOException {
        byte[] pdfBytes = is.readAllBytes();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractFromDocx(InputStream is) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    private String extractFromPlainText(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }
}
