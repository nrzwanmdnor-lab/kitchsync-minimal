package com.kitchsync.app.ingestion;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Apache PDFBox-Android Ingestion Engine with PDF text stripper and ML Kit OCR fallback.
 */
public class PdfBoxIngestion {
    private static final String TAG = "PdfBoxIngestion";
    private static volatile boolean sInitialized = false;

    public static void init(Context context) {
        if (!sInitialized && context != null) {
            PDFBoxResourceLoader.init(context.getApplicationContext());
            sInitialized = true;
        }
    }

    public static class Result {
        public final String text;
        public final boolean ocrFallbackUsed;
        public final int pageCount;

        public Result(String text, boolean ocrFallbackUsed, int pageCount) {
            this.text = text != null ? text.trim() : "";
            this.ocrFallbackUsed = ocrFallbackUsed;
            this.pageCount = pageCount;
        }
    }

    /**
     * Extracts text from an input stream using Apache PDFBox PDFTextStripper,
     * falling back to Google ML Kit OCR on rendered page images if stripper returns little/no text.
     */
    public static Result extractPdf(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            throw new IllegalArgumentException("PDF InputStream cannot be null");
        }

        PDDocument document = null;
        try {
            document = PDDocument.load(inputStream);
            int pageCount = document.getNumberOfPages();

            // 1. Primary Extraction: Apache PDFBox PDFTextStripper
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String strippedText = stripper.getText(document);

            // 2. OCR Fallback: If stripped text is null or very short (< 20 characters),
            // it indicates a scanned PDF or rasterized docket photo embedded in PDF.
            if (strippedText == null || strippedText.trim().length() < 20) {
                Log.i(TAG, "PDFTextStripper extracted insufficient text (" +
                        (strippedText != null ? strippedText.trim().length() : 0) +
                        " chars). Initiating Google ML Kit OCR fallback...");

                PDFRenderer renderer = new PDFRenderer(document);
                StringBuilder ocrText = new StringBuilder();

                for (int page = 0; page < pageCount; page++) {
                    Bitmap pageBitmap = null;
                    try {
                        // Render at 150 DPI for crisp ML Kit character detection
                        pageBitmap = renderer.renderImageWithDPI(page, 150);
                        String pageOcr = MlKitIngestion.recognizeBitmap(pageBitmap);
                        if (pageOcr != null && !pageOcr.trim().isEmpty()) {
                            ocrText.append(pageOcr.trim()).append("\n\n");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "OCR fallback failed on page " + page, e);
                    } finally {
                        if (pageBitmap != null && !pageBitmap.isRecycled()) {
                            pageBitmap.recycle();
                        }
                    }
                }

                String finalOcr = ocrText.toString().trim();
                if (!finalOcr.isEmpty()) {
                    return new Result(finalOcr, true, pageCount);
                }
            }

            return new Result(strippedText, false, pageCount);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Extracts text from Base64 encoded PDF string.
     */
    public static Result extractBase64(String base64Data) throws Exception {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            throw new IllegalArgumentException("PDF data cannot be empty");
        }

        int commaIdx = base64Data.indexOf(',');
        if (commaIdx != -1) {
            base64Data = base64Data.substring(commaIdx + 1);
        }

        byte[] pdfBytes = Base64.decode(base64Data, Base64.DEFAULT);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(pdfBytes)) {
            return extractPdf(bais);
        }
    }
}
