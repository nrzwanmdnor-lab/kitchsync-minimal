package com.kitchsync.app.ingestion;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache POI Word Ingestion Engine using XWPFDocument for .docx and HWPF for .doc,
 * extracting both tables and paragraphs in document order.
 */
public class PoiWordIngestion {
    private static final String TAG = "PoiWordIngestion";

    static {
        // Configure StAX Parser implementations for Android runtime
        try {
            System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl");
            System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl");
            System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl");
        } catch (Throwable t) {
            Log.w(TAG, "Error configuring StAX system properties", t);
        }
    }

    public static class Result {
        public final String text;
        public final String format; // "DOCX" or "DOC"
        public final int paragraphCount;
        public final int tableCount;

        public Result(String text, String format, int paragraphCount, int tableCount) {
            this.text = text != null ? text.trim() : "";
            this.format = format;
            this.paragraphCount = paragraphCount;
            this.tableCount = tableCount;
        }
    }

    /**
     * Extracts text from Word documents (.docx or .doc), parsing both paragraphs and tables.
     */
    public static Result extractWord(InputStream inputStream, String filename) throws Exception {
        if (inputStream == null) {
            throw new IllegalArgumentException("Word document InputStream cannot be null");
        }

        boolean isLegacyDoc = filename != null && filename.toLowerCase().endsWith(".doc");

        if (!isLegacyDoc) {
            try {
                return extractDocx(inputStream);
            } catch (Exception docxEx) {
                Log.w(TAG, "Failed reading with XWPFDocument, attempting HWPF fallback...", docxEx);
                // In case it's actually an OLE2 .doc disguised as .docx or stream needs retry
            }
        }

        // Try HWPFDocument for .doc
        return extractDoc(inputStream);
    }

    /**
     * Parses modern .docx files using Apache POI XWPFDocument for tables and paragraphs.
     */
    public static Result extractDocx(InputStream inputStream) throws Exception {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder sb = new StringBuilder();
            int paragraphCount = 0;
            int tableCount = 0;

            List<IBodyElement> bodyElements = document.getBodyElements();
            if (bodyElements != null && !bodyElements.isEmpty()) {
                for (IBodyElement elem : bodyElements) {
                    if (elem instanceof XWPFParagraph) {
                        XWPFParagraph p = (XWPFParagraph) elem;
                        String pt = p.getText();
                        if (pt != null && !pt.trim().isEmpty()) {
                            sb.append(pt.trim()).append("\n");
                            paragraphCount++;
                        }
                    } else if (elem instanceof XWPFTable) {
                        XWPFTable table = (XWPFTable) elem;
                        tableCount++;
                        for (XWPFTableRow row : table.getRows()) {
                            List<String> cells = new ArrayList<>();
                            for (XWPFTableCell cell : row.getTableCells()) {
                                String ct = cell.getText();
                                if (ct != null) {
                                    ct = ct.trim().replace("\r", " ").replace("\n", " ");
                                    if (!ct.isEmpty()) {
                                        cells.add(ct);
                                    }
                                }
                            }
                            if (!cells.isEmpty()) {
                                sb.append(TextUtils.join(" | ", cells)).append("\n");
                            }
                        }
                    }
                }
            } else {
                // Fallback to separate paragraphs and tables if getBodyElements is empty
                for (XWPFParagraph p : document.getParagraphs()) {
                    String pt = p.getText();
                    if (pt != null && !pt.trim().isEmpty()) {
                        sb.append(pt.trim()).append("\n");
                        paragraphCount++;
                    }
                }
                for (XWPFTable table : document.getTables()) {
                    tableCount++;
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String ct = cell.getText();
                            if (ct != null && !ct.trim().isEmpty()) {
                                cells.add(ct.trim());
                            }
                        }
                        if (!cells.isEmpty()) {
                            sb.append(TextUtils.join(" | ", cells)).append("\n");
                        }
                    }
                }
            }

            return new Result(sb.toString(), "DOCX", paragraphCount, tableCount);
        }
    }

    /**
     * Parses legacy Word 97-2003 .doc files using Apache POI HWPF WordExtractor.
     */
    public static Result extractDoc(InputStream inputStream) throws Exception {
        try (HWPFDocument hwpf = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(hwpf)) {
            StringBuilder sb = new StringBuilder();
            String[] paragraphs = extractor.getParagraphText();
            int count = 0;
            if (paragraphs != null) {
                for (String p : paragraphs) {
                    if (p != null && !p.trim().isEmpty()) {
                        sb.append(p.trim()).append("\n");
                        count++;
                    }
                }
            }
            return new Result(sb.toString(), "DOC", count, 0);
        }
    }

    /**
     * Extracts text from Base64 encoded Word document data.
     */
    public static Result extractBase64(String base64Data, String filename) throws Exception {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            throw new IllegalArgumentException("Word document data cannot be empty");
        }

        int commaIdx = base64Data.indexOf(',');
        if (commaIdx != -1) {
            base64Data = base64Data.substring(commaIdx + 1);
        }

        byte[] docBytes = Base64.decode(base64Data, Base64.DEFAULT);

        // If .doc or ambiguous, try docx first then doc with clean stream resets
        try (ByteArrayInputStream bais = new ByteArrayInputStream(docBytes)) {
            return extractWord(bais, filename);
        }
    }
}
