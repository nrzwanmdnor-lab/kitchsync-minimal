package com.kitchsync.app.ingestion;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaScript Interface Bridge connecting WebView frontend to:
 * 1. Apache PDFBox-Android (PDF text stripper + OCR fallback)
 * 2. Apache POI (XWPFDocument for .docx/.doc tables & paragraphs)
 * 3. Google ML Kit Text Recognition for photos/screenshots
 */
public class IngestionBridge {
    private static final String TAG = "IngestionBridge";

    private final Activity activity;
    private final WebView webView;
    private final ExecutorService executor;

    public IngestionBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.executor = Executors.newFixedThreadPool(2);
        // Initialize PDFBox resource loader with activity context
        PdfBoxIngestion.init(activity);
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    /**
     * Synchronous extraction for Apache POI Word documents.
     */
    @JavascriptInterface
    public String extractWordSync(String base64Data, String filename) {
        try {
            PoiWordIngestion.Result res = PoiWordIngestion.extractBase64(base64Data, filename);
            JSONObject obj = new JSONObject();
            obj.put("success", true);
            obj.put("text", res.text);
            obj.put("method", "Apache POI (" + res.format + ")");
            obj.put("tables", res.tableCount);
            obj.put("paragraphs", res.paragraphCount);
            return obj.toString();
        } catch (Exception e) {
            Log.e(TAG, "POI extraction failed", e);
            return makeErrorJson(e.getMessage());
        }
    }

    /**
     * Synchronous extraction for Apache PDFBox-Android documents with OCR fallback.
     */
    @JavascriptInterface
    public String extractPdfSync(String base64Data, String filename) {
        try {
            PdfBoxIngestion.Result res = PdfBoxIngestion.extractBase64(base64Data);
            JSONObject obj = new JSONObject();
            obj.put("success", true);
            obj.put("text", res.text);
            obj.put("method", res.ocrFallbackUsed ? "Apache PDFBox (OCR Fallback)" : "Apache PDFBox (Text Stripper)");
            obj.put("ocrFallback", res.ocrFallbackUsed);
            obj.put("pageCount", res.pageCount);
            return obj.toString();
        } catch (Exception e) {
            Log.e(TAG, "PDFBox extraction failed", e);
            return makeErrorJson(e.getMessage());
        }
    }

    /**
     * Synchronous extraction for Google ML Kit Text Recognition on photos/screenshots.
     */
    @JavascriptInterface
    public String extractImageSync(String base64Data) {
        try {
            String text = MlKitIngestion.recognizeBase64(base64Data);
            JSONObject obj = new JSONObject();
            obj.put("success", true);
            obj.put("text", text);
            obj.put("method", "Google ML Kit Text Recognition");
            return obj.toString();
        } catch (Exception e) {
            Log.e(TAG, "ML Kit OCR failed", e);
            return makeErrorJson(e.getMessage());
        }
    }

    /**
     * Unified asynchronous file processor.
     * Routes file to Apache PDFBox, Apache POI, or Google ML Kit depending on file metadata,
     * and notifies JavaScript callback when finished.
     */
    @JavascriptInterface
    public void processFileAsync(String base64Data, String filename, String mimeType, String callbackId) {
        executor.execute(() -> {
            try {
                String fn = filename != null ? filename.toLowerCase() : "";
                String mt = mimeType != null ? mimeType.toLowerCase() : "";

                JSONObject obj = new JSONObject();

                if (fn.endsWith(".pdf") || mt.contains("application/pdf")) {
                    // 1. Apache PDFBox-Android (PDF text stripper + OCR fallback)
                    Log.i(TAG, "Ingesting PDF via Apache PDFBox-Android: " + filename);
                    PdfBoxIngestion.Result res = PdfBoxIngestion.extractBase64(base64Data);
                    obj.put("success", true);
                    obj.put("text", res.text);
                    obj.put("tool", "pdfbox");
                    obj.put("method", res.ocrFallbackUsed ? "Apache PDFBox (OCR Fallback)" : "Apache PDFBox (Text Stripper)");
                    obj.put("ocrFallback", res.ocrFallbackUsed);
                    obj.put("pageCount", res.pageCount);

                } else if (fn.endsWith(".docx") || fn.endsWith(".doc") || mt.contains("word") || mt.contains("officedocument")) {
                    // 2. Apache POI (XWPFDocument for .docx/.doc tables & paragraphs)
                    Log.i(TAG, "Ingesting Word doc via Apache POI: " + filename);
                    PoiWordIngestion.Result res = PoiWordIngestion.extractBase64(base64Data, filename);
                    obj.put("success", true);
                    obj.put("text", res.text);
                    obj.put("tool", "poi");
                    obj.put("method", "Apache POI (XWPFDocument)");
                    obj.put("format", res.format);
                    obj.put("tables", res.tableCount);
                    obj.put("paragraphs", res.paragraphCount);

                } else if (mt.startsWith("image/") || fn.matches(".*\\.(jpe?g|png|webp|bmp|gif)$")) {
                    // 3. Google ML Kit Text Recognition for photos/screenshots
                    Log.i(TAG, "Ingesting image via Google ML Kit Text Recognition: " + filename);
                    String text = MlKitIngestion.recognizeBase64(base64Data);
                    obj.put("success", true);
                    obj.put("text", text);
                    obj.put("tool", "mlkit");
                    obj.put("method", "Google ML Kit Text Recognition");

                } else {
                    // Text or other format fallback
                    try {
                        byte[] raw = android.util.Base64.decode(
                                base64Data.contains(",") ? base64Data.substring(base64Data.indexOf(',') + 1) : base64Data,
                                android.util.Base64.DEFAULT);
                        String str = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                        obj.put("success", true);
                        obj.put("text", str);
                        obj.put("tool", "text");
                        obj.put("method", "Plain Text Ingestion");
                    } catch (Exception parseEx) {
                        throw new IllegalArgumentException("Unsupported file type: " + filename);
                    }
                }

                dispatchResult(callbackId, obj.toString());

            } catch (Exception e) {
                Log.e(TAG, "Ingestion error for file: " + filename, e);
                dispatchResult(callbackId, makeErrorJson(e.getMessage()));
            }
        });
    }

    private void dispatchResult(String callbackId, String jsonPayload) {
        activity.runOnUiThread(() -> {
            // Escape any special characters for evaluateJavascript
            String js = "if(window.onNativeIngestionComplete){ window.onNativeIngestionComplete('" +
                    escapeJsString(callbackId) + "', " + jsonPayload + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    private String makeErrorJson(String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("error", msg != null ? msg : "Unknown ingestion error");
            return obj.toString();
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"Unknown error\"}";
        }
    }

    private String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }
}
